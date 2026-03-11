package com.citi.assist.backend.store

import com.citi.assist.backend.store.db.DatabaseManager
import com.google.gson.Gson
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Platform-independent session store. Provides the same SQL persistence
 * as the IntelliJ SessionStore but without MessageBus or IDE dependencies.
 *
 * Events are pushed explicitly via [handleEvent] rather than via subscription.
 */
class SessionStoreCore(private val projectSlug: String) {

    private val log = Logger.getLogger(SessionStoreCore::class.java.name)
    private val gson = Gson()
    private var conn: Connection? = null

    private val lastPrompt = ConcurrentHashMap<String, String>()
    private val currentTurnId = ConcurrentHashMap<String, String>()
    private val liveStatus = ConcurrentHashMap<String, SessionStatus>()

    /** Status change callback — set by the server to push updates to the client */
    var onStatusChanged: ((sessionId: String, status: SessionStatus) -> Unit)? = null

    fun getStatus(sessionId: String): SessionStatus? = liveStatus[sessionId]

    fun init() {
        conn = DatabaseManager.getSessionsConnection(projectSlug)
        log.info("SessionStoreCore initialized for project: $projectSlug")
    }

    private fun db(): Connection {
        return conn ?: throw IllegalStateException("SessionStoreCore not initialized")
    }

    // -- Event handling (called by server when events arrive from the extension) --

    fun handleEvent(sessionId: String, event: SessionEvent) {
        when (event) {
            is SessionEvent.SessionReady -> onSessionReady(event)
            is SessionEvent.TurnIdSync -> onTurnIdSync(sessionId, event)
            is SessionEvent.Reply -> onReply(sessionId, event)
            is SessionEvent.ToolCallUpdate -> onToolCallUpdate(sessionId, event)
            is SessionEvent.Steps -> onSteps(sessionId, event)
            is SessionEvent.Complete -> onComplete(sessionId, event)
            is SessionEvent.Error -> onError(sessionId, event)
            is SessionEvent.Cancel -> onCancel(sessionId, event)
        }
    }

    fun recordPrompt(sessionId: String, prompt: String) {
        lastPrompt[sessionId] = prompt
    }

    // -- Playbook management --

    fun createPlaybook(): String {
        val id = UUID.randomUUID().toString()
        db().prepareStatement("INSERT INTO playbook_runs (id, start_time) VALUES (?, ?)").use { stmt ->
            stmt.setString(1, id)
            stmt.setLong(2, System.currentTimeMillis())
            stmt.executeUpdate()
        }
        return id
    }

    fun assignSessionToPlaybook(sessionId: String, playbookId: String) {
        db().prepareStatement("UPDATE chat_sessions SET playbook_id = ? WHERE session_id = ?").use { stmt ->
            stmt.setString(1, playbookId)
            stmt.setString(2, sessionId)
            stmt.executeUpdate()
        }
    }

    fun completePlaybook(playbookId: String) {
        db().prepareStatement("UPDATE playbook_runs SET end_time = ? WHERE id = ?").use { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            stmt.setString(2, playbookId)
            stmt.executeUpdate()
        }
    }

    // -- Queries --

    fun getSession(sessionId: String): ChatSession? {
        return db().prepareStatement("SELECT * FROM chat_sessions WHERE session_id = ?").use { stmt ->
            stmt.setString(1, sessionId)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.toChatSession() else null
        }
    }

    fun allSessions(): List<ChatSession> {
        val result = mutableListOf<ChatSession>()
        db().createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM chat_sessions ORDER BY start_time DESC")
            while (rs.next()) {
                result.add(rs.toChatSession())
            }
        }
        return result
    }

    fun allPlaybooks(): List<PlaybookRun> {
        val result = mutableListOf<PlaybookRun>()
        db().createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM playbook_runs ORDER BY start_time DESC")
            while (rs.next()) {
                result.add(rs.toPlaybookRun())
            }
        }
        return result
    }

    // -- Internal queries --

    private fun ResultSet.toPlaybookRun(): PlaybookRun {
        val id = getString("id")
        val sessions = getChatSessionsForPlaybook(id)
        return PlaybookRun(
            id = id,
            startTime = getLong("start_time"),
            endTime = getLong("end_time").takeIf { !wasNull() },
            chatSessions = sessions.toMutableList(),
        )
    }

    private fun ResultSet.toChatSession(): ChatSession {
        val sid = getString("session_id")
        val entries = getEntriesForSession(sid)
        return ChatSession(
            sessionId = sid,
            playbookId = getString("playbook_id"),
            startTime = getLong("start_time"),
            endTime = getLong("end_time").takeIf { !wasNull() },
            status = SessionStatus.valueOf(getString("status")),
            entries = entries.toMutableList(),
        )
    }

    private fun getChatSessionsForPlaybook(playbookId: String): List<ChatSession> {
        val result = mutableListOf<ChatSession>()
        db().prepareStatement("SELECT * FROM chat_sessions WHERE playbook_id = ? ORDER BY start_time ASC").use { stmt ->
            stmt.setString(1, playbookId)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                result.add(rs.toChatSession())
            }
        }
        return result
    }

    private fun getEntriesForSession(sessionId: String): List<SessionEntry> {
        val result = mutableListOf<SessionEntry>()
        db().prepareStatement("SELECT * FROM session_entries WHERE chat_session_id = ? ORDER BY start_time ASC").use { stmt ->
            stmt.setString(1, sessionId)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val entryType = rs.getString("entry_type")
                val id = rs.getString("id")
                val chatSessionId = rs.getString("chat_session_id")
                val turnId = rs.getString("turn_id")
                val startTime = rs.getLong("start_time")
                val endTime = rs.getLong("end_time").takeIf { !rs.wasNull() }
                val status = rs.getString("status")

                when (entryType) {
                    "message" -> result.add(SessionEntry.Message(
                        id = id, chatSessionId = chatSessionId, turnId = turnId,
                        startTime = startTime, endTime = endTime, status = status,
                        prompt = rs.getString("prompt"),
                        response = rs.getString("response"),
                        replyLength = rs.getInt("reply_length"),
                    ))
                    "tool_call" -> result.add(SessionEntry.ToolCall(
                        id = id, chatSessionId = chatSessionId, turnId = turnId,
                        startTime = startTime, endTime = endTime, status = status,
                        toolName = rs.getString("tool_name"),
                        toolType = rs.getString("tool_type"),
                        input = rs.getString("input"),
                        inputMessage = rs.getString("input_message"),
                        output = rs.getString("output"),
                        error = rs.getString("error"),
                        progressMessage = rs.getString("progress_message"),
                        roundId = rs.getInt("round_id").takeIf { !rs.wasNull() },
                        durationFromAgent = rs.getLong("duration_ms").takeIf { !rs.wasNull() },
                    ))
                    "step" -> result.add(SessionEntry.Step(
                        id = id, chatSessionId = chatSessionId, turnId = turnId,
                        startTime = startTime, endTime = endTime, status = status,
                        title = rs.getString("title"),
                        description = rs.getString("description"),
                    ))
                }
            }
        }
        return result
    }

    // -- Event handlers --

    private fun publishStatus(sessionId: String, status: SessionStatus) {
        liveStatus[sessionId] = status
        onStatusChanged?.invoke(sessionId, status)
    }

    private fun onSessionReady(event: SessionEvent.SessionReady) {
        val exists = db().prepareStatement("SELECT 1 FROM chat_sessions WHERE session_id = ?").use { stmt ->
            stmt.setString(1, event.sessionId)
            stmt.executeQuery().next()
        }

        if (exists) {
            db().prepareStatement("UPDATE chat_sessions SET start_time = ?, status = ? WHERE session_id = ?").use { stmt ->
                stmt.setLong(1, event.timestamp)
                stmt.setString(2, SessionStatus.ACTIVE.name)
                stmt.setString(3, event.sessionId)
                stmt.executeUpdate()
            }
        } else {
            db().prepareStatement("INSERT INTO chat_sessions (session_id, start_time, status) VALUES (?, ?, ?)").use { stmt ->
                stmt.setString(1, event.sessionId)
                stmt.setLong(2, event.timestamp)
                stmt.setString(3, SessionStatus.ACTIVE.name)
                stmt.executeUpdate()
            }
        }
        publishStatus(event.sessionId, SessionStatus.ACTIVE)
    }

    private fun onTurnIdSync(sessionId: String, event: SessionEvent.TurnIdSync) {
        currentTurnId[sessionId] = event.turnId

        val exists = db().prepareStatement("SELECT 1 FROM session_entries WHERE id = ?").use { stmt ->
            stmt.setString(1, event.turnId)
            stmt.executeQuery().next()
        }

        if (!exists) {
            db().prepareStatement(
                "INSERT INTO session_entries (id, chat_session_id, entry_type, turn_id, start_time, status, prompt) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, event.turnId)
                stmt.setString(2, sessionId)
                stmt.setString(3, "message")
                stmt.setString(4, event.turnId)
                stmt.setLong(5, event.timestamp)
                stmt.setString(6, "streaming")
                stmt.setString(7, lastPrompt.remove(sessionId))
                stmt.executeUpdate()
            }
        }
    }

    private fun onReply(sessionId: String, event: SessionEvent.Reply) {
        val turnId = currentTurnId[sessionId] ?: event.parentTurnId ?: return
        db().prepareStatement("UPDATE session_entries SET response = ?, reply_length = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, event.accumulated)
            stmt.setInt(2, event.accumulated.length)
            stmt.setString(3, turnId)
            stmt.executeUpdate()
        }
    }

    private fun onToolCallUpdate(sessionId: String, event: SessionEvent.ToolCallUpdate) {
        val toolCallId = event.toolCallId ?: return
        val exists = db().prepareStatement("SELECT 1 FROM session_entries WHERE id = ?").use { stmt ->
            stmt.setString(1, toolCallId)
            stmt.executeQuery().next()
        }

        val turnId = event.turnId ?: currentTurnId[sessionId]

        if (exists) {
            db().prepareStatement(
                """UPDATE session_entries SET status = ?, error = ?, duration_ms = ?,
                   output = COALESCE(?, output), progress_message = COALESCE(?, progress_message),
                   input_message = COALESCE(?, input_message),
                   end_time = CASE WHEN ? != 'running' THEN ? ELSE end_time END WHERE id = ?"""
            ).use { stmt ->
                stmt.setString(1, event.status ?: "unknown")
                stmt.setString(2, event.error)
                if (event.durationMs != null) stmt.setLong(3, event.durationMs) else stmt.setNull(3, java.sql.Types.BIGINT)
                stmt.setString(4, event.output)
                stmt.setString(5, event.progressMessage)
                stmt.setString(6, event.inputMessage)
                stmt.setString(7, event.status ?: "unknown")
                stmt.setLong(8, event.timestamp)
                stmt.setString(9, toolCallId)
                stmt.executeUpdate()
            }
        } else {
            db().prepareStatement(
                """INSERT INTO session_entries (id, chat_session_id, entry_type, turn_id, start_time, status,
                   tool_name, tool_type, input, input_message, output, error, progress_message, round_id)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { stmt ->
                stmt.setString(1, toolCallId)
                stmt.setString(2, sessionId)
                stmt.setString(3, "tool_call")
                stmt.setString(4, turnId)
                stmt.setLong(5, event.timestamp)
                stmt.setString(6, event.status ?: "running")
                stmt.setString(7, event.toolName)
                stmt.setString(8, event.toolType)
                stmt.setString(9, event.input)
                stmt.setString(10, event.inputMessage)
                stmt.setString(11, event.output)
                stmt.setString(12, event.error)
                stmt.setString(13, event.progressMessage)
                if (event.roundId != null) stmt.setInt(14, event.roundId) else stmt.setNull(14, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    private fun onSteps(sessionId: String, event: SessionEvent.Steps) {
        val turnId = currentTurnId[sessionId] ?: event.parentTurnId
        for (step in event.steps) {
            val stepId = step.id ?: continue
            val exists = db().prepareStatement("SELECT 1 FROM session_entries WHERE id = ?").use { stmt ->
                stmt.setString(1, stepId)
                stmt.executeQuery().next()
            }

            if (exists) {
                db().prepareStatement(
                    "UPDATE session_entries SET status = ?, title = COALESCE(?, title), description = COALESCE(?, description) WHERE id = ?"
                ).use { stmt ->
                    stmt.setString(1, step.status ?: "running")
                    stmt.setString(2, step.title)
                    stmt.setString(3, step.description)
                    stmt.setString(4, stepId)
                    stmt.executeUpdate()
                }
            } else {
                db().prepareStatement(
                    """INSERT INTO session_entries (id, chat_session_id, entry_type, turn_id, start_time, status, title, description)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
                ).use { stmt ->
                    stmt.setString(1, stepId)
                    stmt.setString(2, sessionId)
                    stmt.setString(3, "step")
                    stmt.setString(4, turnId)
                    stmt.setLong(5, event.timestamp)
                    stmt.setString(6, step.status ?: "running")
                    stmt.setString(7, step.title)
                    stmt.setString(8, step.description)
                    stmt.executeUpdate()
                }
            }
        }
    }

    private fun onComplete(sessionId: String, event: SessionEvent.Complete) {
        db().prepareStatement("UPDATE chat_sessions SET status = ?, end_time = ? WHERE session_id = ?").use { stmt ->
            stmt.setString(1, SessionStatus.COMPLETED.name)
            stmt.setLong(2, event.timestamp)
            stmt.setString(3, sessionId)
            stmt.executeUpdate()
        }
        db().prepareStatement(
            "UPDATE session_entries SET end_time = ?, status = ? WHERE chat_session_id = ? AND entry_type = 'message' AND end_time IS NULL"
        ).use { stmt ->
            stmt.setLong(1, event.timestamp)
            stmt.setString(2, "complete")
            stmt.setString(3, sessionId)
            stmt.executeUpdate()
        }
        publishStatus(sessionId, SessionStatus.COMPLETED)
        cleanupSessionMaps(sessionId)
    }

    private fun onError(sessionId: String, event: SessionEvent.Error) {
        db().prepareStatement("UPDATE chat_sessions SET status = ?, end_time = ? WHERE session_id = ?").use { stmt ->
            stmt.setString(1, SessionStatus.ERROR.name)
            stmt.setLong(2, event.timestamp)
            stmt.setString(3, sessionId)
            stmt.executeUpdate()
        }
        publishStatus(sessionId, SessionStatus.ERROR)
        cleanupSessionMaps(sessionId)
    }

    private fun onCancel(sessionId: String, event: SessionEvent.Cancel) {
        db().prepareStatement("UPDATE chat_sessions SET status = ?, end_time = ? WHERE session_id = ?").use { stmt ->
            stmt.setString(1, SessionStatus.CANCELLED.name)
            stmt.setLong(2, event.timestamp)
            stmt.setString(3, sessionId)
            stmt.executeUpdate()
        }
        publishStatus(sessionId, SessionStatus.CANCELLED)
        cleanupSessionMaps(sessionId)
    }

    private fun cleanupSessionMaps(sessionId: String) {
        lastPrompt.remove(sessionId)
        currentTurnId.remove(sessionId)
    }
}
