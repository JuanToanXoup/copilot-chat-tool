package com.citi.assist.model

import com.github.copilot.chat.conversation.agent.rpc.ConfirmationRequest
import com.github.copilot.chat.conversation.agent.rpc.Unauthorized
import com.github.copilot.chat.conversation.agent.rpc.message.AgentRound
import com.github.copilot.chat.conversation.agent.rpc.message.AgentToolCall
import com.github.copilot.chat.conversation.agent.rpc.message.Step

sealed class SilentChatEvent {
    /** Epoch millis when this event was created */
    val timestamp: Long = System.currentTimeMillis()

    data class SessionReady(val sessionId: String) : SilentChatEvent()
    data object Begin : SilentChatEvent()
    data class ConversationIdSync(val conversationId: String) : SilentChatEvent()
    data class TurnIdSync(val turnId: String, val parentTurnId: String?) : SilentChatEvent()
    data class References(val references: List<*>, val parentTurnId: String?) : SilentChatEvent()
    data class Steps(val steps: List<Step>, val parentTurnId: String?) : SilentChatEvent()
    data class ConfirmationRequest(val request: com.github.copilot.chat.conversation.agent.rpc.ConfirmationRequest) : SilentChatEvent()
    data class Notifications(val notifications: List<*>) : SilentChatEvent()
    data class Reply(val delta: String, val accumulated: String, val annotations: List<*>, val parentTurnId: String?) : SilentChatEvent()
    data class EditAgentRound(val round: AgentRound, val parentTurnId: String?) : SilentChatEvent()
    data class UpdatedDocuments(val documents: List<*>) : SilentChatEvent()
    data class SuggestedTitle(val title: String) : SilentChatEvent()
    data class Complete(val fullReply: String) : SilentChatEvent()
    data class Filter(val message: String?) : SilentChatEvent()
    data class Error(val message: String, val code: Int = 0, val reason: String? = null, val modelName: String? = null, val modelProviderName: String? = null) : SilentChatEvent()
    data class Unauthorized(val unauthorized: com.github.copilot.chat.conversation.agent.rpc.Unauthorized) : SilentChatEvent()
    data object Cancel : SilentChatEvent()
    data class ModelInformation(val modelName: String?, val modelProviderName: String?, val modelBillingMultiplier: String?) : SilentChatEvent()

    /**
     * Fired when a tool call is first seen (status=running) and again when it completes/fails.
     * Extracted from AgentRound.toolCalls in editAgentRounds progress events.
     *
     * - On first appearance: status is typically "running", result is null, durationMs is null
     * - On completion: status is "completed"/"failed", result and durationMs are populated
     */
    data class ToolCallUpdate(
        val sessionId: String,
        val turnId: String?,
        val parentTurnId: String?,
        val roundId: Int,
        val toolCallId: String?,
        val toolName: String?,
        val toolType: String?,
        val input: Map<String, Any>?,
        val inputMessage: String?,
        val status: String?,
        val result: List<ToolCallResult>?,
        val error: String?,
        val progressMessage: String?,
        val durationMs: Long?,
    ) : SilentChatEvent()

    /**
     * Simplified view of AgentToolCall result data.
     */
    data class ToolCallResult(
        val type: String?,
        val value: Any?,
    )
}
