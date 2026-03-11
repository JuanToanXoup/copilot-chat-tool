package com.github.copilotsilent.orchestrator

import com.github.copilot.chat.conversation.agent.rpc.command.ChatMode
import com.github.copilot.chat.conversation.agent.rpc.command.CopilotModel
import com.github.copilotsilent.model.SilentChatEvent
import com.github.copilotsilent.model.SilentChatListener
import com.github.copilotsilent.service.CopilotSilentChatService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates sequential and parallel message dispatching.
 *
 * - sendParallel(): each request gets its own session, all run concurrently
 * - sendSequential(): requests queue on a single session, each waits for the previous to complete
 */
@Service(Service.Level.PROJECT)
class ChatOrchestrator(
    private val project: Project,
) : Disposable {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun dispose() {
        coroutineScope.cancel()
    }
    private val log = Logger.getInstance(ChatOrchestrator::class.java)

    private val service: CopilotSilentChatService
        get() = project.service<CopilotSilentChatService>()

    /**
     * Send multiple messages in parallel — each gets its own new session.
     */
    fun sendParallel(requests: List<ChatRequest>) {
        for (request in requests) {
            service.sendMessage(
                message = request.message,
                model = request.model,
                mode = request.mode,
                newSession = true,
                silent = request.silent,
            )
        }
    }

    /**
     * Send multiple messages sequentially on the same session.
     * Each message waits for the previous to complete before sending.
     *
     * Subscribes once to the MessageBus for the entire chain. Uses an
     * AtomicReference to track the current session ID so the subscriber
     * only reacts to events for the active step's session.
     */
    fun sendSequential(
        requests: List<ChatRequest>,
        sessionId: String? = null,
    ) {
        coroutineScope.launch {
            val currentSid = AtomicReference(sessionId)
            val currentCompletion = AtomicReference<CompletableDeferred<String?>>()

            val conn = project.messageBus.connect()
            conn.subscribe(SilentChatListener.TOPIC, object : SilentChatListener {
                override fun onEvent(eventSessionId: String, event: SilentChatEvent) {
                    val expectedSid = currentSid.get()

                    when (event) {
                        is SilentChatEvent.SessionReady -> {
                            // Accept SessionReady only when we're waiting for a new session
                            // (expectedSid is null) — this locks us to that session
                            if (expectedSid == null) {
                                currentSid.set(event.sessionId)
                            }
                        }
                        is SilentChatEvent.Complete -> {
                            if (eventSessionId == currentSid.get()) {
                                currentCompletion.get()?.complete(currentSid.get())
                            }
                        }
                        is SilentChatEvent.Error -> {
                            if (eventSessionId == currentSid.get()) {
                                currentCompletion.get()?.complete(null)
                            }
                        }
                        else -> {}
                    }
                }
            })

            try {
                for ((index, request) in requests.withIndex()) {
                    val completion = CompletableDeferred<String?>()
                    currentCompletion.set(completion)

                    service.sendMessage(
                        message = request.message,
                        sessionId = currentSid.get(),
                        model = request.model,
                        mode = request.mode,
                        newSession = (currentSid.get() == null && index == 0),
                        silent = request.silent,
                    )

                    val result = completion.await()
                    if (result == null) {
                        log.warn("Sequential chain stopped at request $index due to error")
                        break
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}

data class ChatRequest(
    val message: String,
    val model: CopilotModel? = null,
    val mode: ChatMode? = null,
    val silent: Boolean = true,
)
