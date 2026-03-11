package com.citi.assist.backend.store

/**
 * Platform-independent session events. These mirror the IntelliJ SilentChatEvent
 * sealed class but use only primitive/String types — no Copilot API dependencies.
 *
 * The VS Code extension serializes these as JSON over stdio; the IntelliJ plugin
 * maps SilentChatEvent → SessionEvent before calling SessionStoreCore.
 */
sealed class SessionEvent {
    val timestamp: Long = System.currentTimeMillis()

    data class SessionReady(val sessionId: String) : SessionEvent()

    data class TurnIdSync(val turnId: String, val parentTurnId: String?) : SessionEvent()

    data class Reply(
        val delta: String,
        val accumulated: String,
        val parentTurnId: String?,
    ) : SessionEvent()

    data class ToolCallUpdate(
        val turnId: String?,
        val parentTurnId: String?,
        val roundId: Int?,
        val toolCallId: String?,
        val toolName: String?,
        val toolType: String?,
        val input: String?,
        val inputMessage: String?,
        val status: String?,
        val output: String?,
        val error: String?,
        val progressMessage: String?,
        val durationMs: Long?,
    ) : SessionEvent()

    data class Steps(
        val steps: List<StepData>,
        val parentTurnId: String?,
    ) : SessionEvent()

    data class StepData(
        val id: String?,
        val status: String?,
        val title: String?,
        val description: String?,
    )

    data class Complete(val fullReply: String) : SessionEvent()

    data class Error(
        val message: String,
        val code: Int = 0,
        val reason: String? = null,
    ) : SessionEvent()

    data object Cancel : SessionEvent()
}
