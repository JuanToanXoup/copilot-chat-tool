package com.github.copilotsilent.backend.store

/**
 * Persistence models for chat sessions and individual entries.
 * Pure data classes — no IDE or framework dependencies.
 *
 * Schema:
 *   playbook_runs  1──N  chat_sessions  1──N  session_entries
 */

data class PlaybookRun(
    val id: String,
    val startTime: Long,
    var endTime: Long? = null,
    val chatSessions: MutableList<ChatSession> = mutableListOf(),
) {
    val durationMs: Long?
        get() = endTime?.let { it - startTime }
}

data class ChatSession(
    val sessionId: String,
    val playbookId: String?,
    val startTime: Long,
    var endTime: Long? = null,
    var status: SessionStatus = SessionStatus.ACTIVE,
    val entries: MutableList<SessionEntry> = mutableListOf(),
) {
    val durationMs: Long?
        get() = endTime?.let { it - startTime }
}

enum class SessionStatus {
    ACTIVE,
    COMPLETED,
    ERROR,
    CANCELLED,
}

sealed class SessionEntry {
    abstract val id: String
    abstract val chatSessionId: String
    abstract val turnId: String?
    abstract val startTime: Long
    abstract val endTime: Long?
    abstract val status: String

    val durationMs: Long?
        get() = endTime?.let { it - startTime }

    data class Message(
        override val id: String,
        override val chatSessionId: String,
        override val turnId: String? = null,
        override val startTime: Long,
        override var endTime: Long? = null,
        override var status: String = "streaming",
        var prompt: String? = null,
        var response: String? = null,
        var replyLength: Int = 0,
    ) : SessionEntry()

    data class ToolCall(
        override val id: String,
        override val chatSessionId: String,
        override val turnId: String? = null,
        override val startTime: Long,
        override var endTime: Long? = null,
        override var status: String = "running",
        val toolName: String?,
        val toolType: String?,
        var input: String? = null,
        var inputMessage: String? = null,
        var output: String? = null,
        var error: String? = null,
        var progressMessage: String? = null,
        var roundId: Int? = null,
        var durationFromAgent: Long? = null,
    ) : SessionEntry()

    data class Step(
        override val id: String,
        override val chatSessionId: String,
        override val turnId: String? = null,
        override val startTime: Long,
        override var endTime: Long? = null,
        override var status: String = "running",
        val title: String?,
        val description: String?,
    ) : SessionEntry()
}
