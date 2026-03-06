package com.github.copilotsilent.store.db

import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definitions for sessions.db
 *
 * Schema:
 *   playbook_runs  1──N  chat_sessions  1──N  session_entries
 */

object PlaybookRuns : Table("playbook_runs") {
    val id = varchar("id", 36)
    val startTime = long("start_time")
    val endTime = long("end_time").nullable()

    override val primaryKey = PrimaryKey(id)
}

object ChatSessions : Table("chat_sessions") {
    val sessionId = varchar("session_id", 64)
    val playbookId = varchar("playbook_id", 36)
        .references(PlaybookRuns.id)
        .nullable()
    val startTime = long("start_time")
    val endTime = long("end_time").nullable()
    val status = varchar("status", 20).default("ACTIVE")

    override val primaryKey = PrimaryKey(sessionId)
}

object SessionEntries : Table("session_entries") {
    val id = varchar("id", 64)
    val chatSessionId = varchar("chat_session_id", 64)
        .references(ChatSessions.sessionId)
    val entryType = varchar("entry_type", 20) // "message" or "tool_call"
    val startTime = long("start_time")
    val endTime = long("end_time").nullable()
    val status = varchar("status", 20).default("running")
    val durationMs = long("duration_ms").nullable()

    // Message fields
    val prompt = text("prompt").nullable()
    val response = text("response").nullable()
    val replyLength = integer("reply_length").nullable()

    // Tool call fields
    val toolName = varchar("tool_name", 128).nullable()
    val toolType = varchar("tool_type", 64).nullable()
    val input = text("input").nullable()
    val output = text("output").nullable()
    val error = text("error").nullable()

    override val primaryKey = PrimaryKey(id)
}
