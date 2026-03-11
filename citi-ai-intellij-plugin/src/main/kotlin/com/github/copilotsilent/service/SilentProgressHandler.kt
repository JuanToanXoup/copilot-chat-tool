package com.github.copilotsilent.service

import com.github.copilot.agent.conversation.AbstractCopilotAgentConversationProgressHandler
import com.github.copilot.chat.conversation.agent.rpc.Unauthorized
import com.github.copilot.chat.conversation.agent.rpc.message.AgentToolCall
import com.github.copilot.chat.conversation.agent.rpc.message.ConversationError
import com.github.copilot.chat.conversation.agent.rpc.message.ConversationProgressValue
import com.github.copilotsilent.model.SilentChatEvent
import com.github.copilotsilent.model.SilentChatListener
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Extends AbstractCopilotAgentConversationProgressHandler — the exact same base class
 * that CopilotAgentProgressHandler (the tool window handler) and
 * CopilotChatServiceImpl.ProgressHandler extend.
 *
 * AbstractCopilotAgentConversationProgressHandler.on() dispatches
 * CopilotAgentConversationProgressEvent subtypes to the on* methods below.
 *
 * Publishes all events to [SilentChatListener.TOPIC] via the project MessageBus.
 */
class SilentProgressHandler(
    private val project: Project,
    private val sessionId: String,
) : AbstractCopilotAgentConversationProgressHandler() {

    private fun publish(event: SilentChatEvent) {
        project.messageBus.syncPublisher(SilentChatListener.TOPIC).onEvent(sessionId, event)
    }

    private val replyBuilder = StringBuilder()

    /** Tracks when we first saw each tool call (by id) for duration calculation */
    private val toolCallStartTimes = ConcurrentHashMap<String, Long>()

    /** Tracks which tool call statuses we've already emitted to detect transitions */
    private val toolCallLastStatus = ConcurrentHashMap<String, String>()

    /** Current turn/parent tracking from progress events */
    private var currentTurnId: String? = null
    private var currentParentTurnId: String? = null

    override fun onBegin() {
        publish(SilentChatEvent.Begin)
    }

    override fun onProgress(progress: ConversationProgressValue) {
        // Sync conversation/turn IDs
        if (progress.conversationId.isNotBlank()) {
            publish(SilentChatEvent.ConversationIdSync(progress.conversationId))
        }
        if (progress.turnId.isNotBlank()) {
            currentTurnId = progress.turnId
            currentParentTurnId = progress.parentTurnId
            publish(SilentChatEvent.TurnIdSync(progress.turnId, progress.parentTurnId))
        }

        // References
        val references = progress.references
        if (references != null) {
            publish(SilentChatEvent.References(references, progress.parentTurnId))
        }

        // Steps (agent tool calls — high-level status)
        val steps = progress.steps
        if (!steps.isNullOrEmpty()) {
            publish(SilentChatEvent.Steps(steps, progress.parentTurnId))
        }

        // Confirmation request
        progress.confirmationRequest?.let {
            publish(SilentChatEvent.ConfirmationRequest(it))
        }

        // Notifications
        val notifications = progress.notifications
        if (!notifications.isNullOrEmpty()) {
            publish(SilentChatEvent.Notifications(notifications))
        }

        // Reply text — capture even when hideText is true (agent mode hides from
        // the Copilot UI, but we still want the text for programmatic consumers)
        val reply = progress.reply
        if (reply != null && reply.isNotBlank()) {
            replyBuilder.append(reply)
            val annotations = progress.annotations ?: emptyList()
            publish(SilentChatEvent.Reply(reply, replyBuilder.toString(), annotations, progress.parentTurnId))
        }

        // Edit agent rounds — extract tool calls with timing and reply text
        val editAgentRounds = progress.editAgentRounds
        if (!editAgentRounds.isNullOrEmpty()) {
            for (round in editAgentRounds) {
                publish(SilentChatEvent.EditAgentRound(round, progress.parentTurnId))
                emitToolCallUpdates(round.roundId, round.toolCalls, progress.parentTurnId)

                // Agent mode may deliver reply text via round.reply instead of progress.reply
                val roundReply = round.reply
                if (roundReply != null && roundReply.isNotBlank()) {
                    replyBuilder.append(roundReply)
                    val annotations = progress.annotations ?: emptyList()
                    publish(SilentChatEvent.Reply(roundReply, replyBuilder.toString(), annotations, progress.parentTurnId))
                }
            }
        }
    }

    override fun onComplete(complete: ConversationProgressValue) {
        // Updated documents
        var updatedDocuments = complete.updatedDocuments
        if (updatedDocuments == null) {
            val codeEdits = complete.codeEdits
            if (codeEdits != null) {
                updatedDocuments = codeEdits.map { it.updatedDocument }
            }
        }
        if (updatedDocuments != null && updatedDocuments.isNotEmpty()) {
            publish(SilentChatEvent.UpdatedDocuments(updatedDocuments))
        }

        // Suggested title
        complete.suggestedTitle?.let {
            publish(SilentChatEvent.SuggestedTitle(it))
        }

        // Final edit agent rounds — emit any remaining tool call completions
        val editAgentRounds = complete.editAgentRounds
        if (!editAgentRounds.isNullOrEmpty()) {
            for (round in editAgentRounds) {
                emitToolCallUpdates(round.roundId, round.toolCalls, complete.parentTurnId)
            }
        }

        // Final reply — capture even when hideText is true (see onProgress comment)
        val reply = complete.reply
        if (reply != null && reply.isNotBlank()) {
            replyBuilder.append(reply)
        }

        // Also extract reply from final edit agent rounds
        if (!editAgentRounds.isNullOrEmpty()) {
            for (round in editAgentRounds) {
                val roundReply = round.reply
                if (roundReply != null && roundReply.isNotBlank()) {
                    replyBuilder.append(roundReply)
                }
            }
        }

        publish(SilentChatEvent.Complete(replyBuilder.toString()))
    }

    override fun onError(error: ConversationError) {
        if (error.responseIsFiltered == true) {
            publish(SilentChatEvent.Filter(error.message))
        } else {
            val code = error.code?.toInt() ?: 0
            publish(SilentChatEvent.Error(
                error.message ?: "Unknown error",
                code,
                error.reason,
                error.modelName,
                error.modelProviderName
            ))
        }
        publish(SilentChatEvent.Complete(replyBuilder.toString()))
    }

    override fun onUnauthorized(unauthorized: Unauthorized) {
        publish(SilentChatEvent.Unauthorized(unauthorized))
    }

    override fun onSyncConversationId(conversationId: String) {
        publish(SilentChatEvent.ConversationIdSync(conversationId))
    }

    override fun onSyncModelInformation(
        modelName: String?,
        modelProviderName: String?,
        modelBillingMultiplier: String?
    ) {
        publish(SilentChatEvent.ModelInformation(modelName, modelProviderName, modelBillingMultiplier))
    }

    override fun onCancel() {
        publish(SilentChatEvent.Cancel)
    }

    /**
     * Extracts tool call data from AgentRound.toolCalls and emits ToolCallUpdate events.
     * Tracks timing: records start time on first appearance, calculates duration on completion.
     * Only emits when a tool call is new or its status has changed.
     */
    private fun emitToolCallUpdates(roundId: Int, toolCalls: List<AgentToolCall>?, parentTurnId: String?) {
        if (toolCalls.isNullOrEmpty()) return

        val now = System.nanoTime()

        for (tc in toolCalls) {
            val id = tc.id ?: continue
            val status = tc.status ?: "unknown"

            // Check if status changed — skip if we already emitted this exact state
            val previousStatus = toolCallLastStatus.put(id, status)
            if (previousStatus == status) continue

            // Track start time
            if (previousStatus == null) {
                toolCallStartTimes[id] = now
            }

            // Calculate duration if the tool call is no longer running
            val isTerminal = status != "running" && status != "unknown"
            val durationMs = if (isTerminal) {
                val startTime = toolCallStartTimes[id]
                if (startTime != null) (now - startTime) / 1_000_000 else null
            } else {
                null
            }

            // Map result data
            val resultData = tc.result?.map { rd ->
                SilentChatEvent.ToolCallResult(rd.type, rd.value)
            }

            publish(SilentChatEvent.ToolCallUpdate(
                sessionId = sessionId,
                turnId = currentTurnId,
                parentTurnId = parentTurnId,
                roundId = roundId,
                toolCallId = id,
                toolName = tc.name,
                toolType = tc.toolType,
                input = tc.input,
                inputMessage = tc.inputMessage,
                status = status,
                result = resultData,
                error = tc.error,
                progressMessage = tc.progressMessage,
                durationMs = durationMs,
            ))

            // Clean up tracking maps for completed tool calls
            if (isTerminal) {
                toolCallStartTimes.remove(id)
            }
        }
    }
}
