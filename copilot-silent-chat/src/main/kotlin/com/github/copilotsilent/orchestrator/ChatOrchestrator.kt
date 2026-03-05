package com.github.copilotsilent.orchestrator

import com.github.copilot.chat.conversation.agent.rpc.command.ChatMode
import com.github.copilot.chat.conversation.agent.rpc.command.CopilotModel
import com.github.copilotsilent.model.SilentChatEvent
import com.github.copilotsilent.service.CopilotSilentChatService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Orchestrates sequential and parallel message dispatching.
 *
 * - sendParallel(): each request gets its own session, all run concurrently
 * - sendSequential(): requests queue on a single session, each waits for the previous to complete
 */
@Service(Service.Level.PROJECT)
class ChatOrchestrator(
    private val project: Project,
    private val coroutineScope: CoroutineScope
) {
    private val log = Logger.getInstance(ChatOrchestrator::class.java)

    private val service: CopilotSilentChatService
        get() = project.service<CopilotSilentChatService>()

    /**
     * Send multiple messages in parallel — each gets its own new session.
     */
    fun sendParallel(
        requests: List<ChatRequest>,
        onEvent: ((index: Int, SilentChatEvent) -> Unit)? = null
    ) {
        for ((index, request) in requests.withIndex()) {
            service.sendMessage(
                message = request.message,
                model = request.model,
                mode = request.mode,
                newSession = true,
                silent = request.silent,
                onEvent = { event -> onEvent?.invoke(index, event) }
            )
        }
    }

    /**
     * Send multiple messages sequentially on the same session.
     * Each message waits for the previous to complete before sending.
     */
    fun sendSequential(
        requests: List<ChatRequest>,
        sessionId: String? = null,
        onEvent: ((index: Int, SilentChatEvent) -> Unit)? = null
    ) {
        coroutineScope.launch {
            var sid = sessionId

            for ((index, request) in requests.withIndex()) {
                val completion = CompletableDeferred<String?>()

                service.sendMessage(
                    message = request.message,
                    sessionId = sid,
                    model = request.model,
                    mode = request.mode,
                    newSession = (sid == null && index == 0),
                    silent = request.silent,
                    onEvent = { event ->
                        onEvent?.invoke(index, event)

                        when (event) {
                            is SilentChatEvent.SessionReady -> {
                                // Capture session ID for subsequent messages
                                sid = event.sessionId
                            }
                            is SilentChatEvent.Complete -> {
                                completion.complete(sid)
                            }
                            is SilentChatEvent.Error -> {
                                completion.complete(null)
                            }
                            else -> {}
                        }
                    }
                )

                // Wait for this message to finish before sending the next
                val result = completion.await()
                if (result == null) {
                    log.warn("Sequential chain stopped at request $index due to error")
                    break
                }
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
