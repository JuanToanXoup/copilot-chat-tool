package com.citi.assist.store

import com.citi.assist.model.ChatStatusListener
import com.citi.assist.model.SilentChatEvent
import com.citi.assist.model.SilentChatListener
import com.citi.assist.store.db.DatabaseManager
import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * Event-sourced session store. Subscribes to [SilentChatListener.TOPIC]
 * and persists [PlaybookRun] / [ChatSession] / [SessionEntry] state to
 * ~/.citi-ai/projects/{project-slug}/sessions.db via raw JDBC.
 */
@Service(Service.Level.PROJECT)
class SessionStore(private val project: Project) {

    companion object {
        const val PENDING_PROMPT_KEY = "__pending__"
    }

    private val log = Logger.getInstance(SessionStore::class.java)
    private val gson = Gson()
    private var conn: Connection? = null
    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Tracks the most recent prompt per session for correlating with replies */
    private val lastPrompt = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Tracks the current turnId per session so Reply events can find the right entry */
    private val currentTurnId = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** In-memory status cache for fast queries */
    private val liveStatus = java.util.concurrent.ConcurrentHashMap<String, SessionStatus>()

    fun getStatus(sessionId: String): SessionStatus? = liveStatus[sessionId]

    private fun publishStatus(sessionId: String, status: SessionStatus) {
        liveStatus[sessionId] = status
        project.messageBus.syncPublisher(ChatStatusListener.TOPIC)
            .onStatusChanged(sessionId, status)
    }

    fun init() {
        log.info("SessionStore.init() called")
        initDb()

        project.messageBus.connect().subscribe(
            SilentChatListener.TOPIC, object : SilentChatListener {
                override fun onEvent(sessionId: String, event: SilentChatEvent) {
                    try {
                        ensureInitialized()
                        handleEvent(sessionId, event)
                    } catch (e: Exception) {
                        log.warn("SessionStore failed to handle event: ${event::class.simpleName}", e)
                    }
                }
            }
        )
        log.info("SessionStore initialized with db at ${DatabaseManager.projectDir(project)}")
    }

    private fun initDb() {
        try {
            conn = DatabaseManager.getSessionsConnection(project)
            initialized.set(true)
            log.info("SessionStore database initialized")
        } catch (e: Exception) {
            log.warn("SessionStore database init failed, will retry on next event", e)
        }
    }

    private fun ensureInitialized() {
        if (initialized.get()) return
        initDb()
        if (!initialized.get()) {
            throw IllegalStateException("SessionStore database not available")
        }
    }

    private fun db(): Connection {
        return conn ?: throw IllegalStateException("SessionStore database not initialized")
    }

    // -- Playbook management --

    fun createPlaybook(): String {
        ensureInitialized()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db().prepareStatement("INSERT INTO playbook_runs (id, start_time) VALUES (?, ?)").use { stmt ->
            stmt.setString(1, id)
            stmt.setLong(2, now)
            stmt.executeUpdate()
        }
        return id
    }

    fun assignSessionToPlaybook(sessionId: String, playbookId: String) {
        ensureInitialized()
        db().prepareStatement("UPDATE chat_sessions SET playbook_id = ? WHERE session_id = ?").use { stmt ->
            stmt.setString(1, playbookId)
            stmt.setString(2, sessionId)
            stmt.executeUpdate()
        }
    }

    fun completePlaybook(playbookId: String) {
        ensureInitialized()
        db().prepareStatement("UPDATE playbook_runs SET end_time = ? WHERE id = ?").use { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            stmt.setString(2, playbookId)
            stmt.executeUpdate()
        }
    }

    fun recordPrompt(sessionId: String, prompt: String) {
        lastPrompt[sessionId] = prompt
    }

    // -- Queries --

    fun getPlaybook(id: String): PlaybookRun? {
        ensureInitialized()
        return db().prepareStatement("SELECT * FROM playbook_runs WHERE id = ?").use { stmt ->
            stmt.setString(1, id)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.toPlaybookRun() else null
        }
    }

    fun getSession(sessionId: String): ChatSession? {
        ensureInitialized()
        return db().prepareStatement("SELECT * FROM chat_sessions WHERE session_id = ?").use { stmt ->
            stmt.setString(1, sessionId)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.toChatSession() else null
        }
    }

    fun allPlaybooks(): List<PlaybookRun> {
        ensureInitialized()
        val result = mutableListOf<PlaybookRun>()
        db().createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM playbook_runs ORDER BY start_time DESC")
            while (rs.next()) {
                result.add(rs.toPlaybookRun())
            }
        }
        return result
    }

    fun allSessions(): List<ChatSession> {
        ensureInitialized()
        val result = mutableListOf<ChatSession>()
        db().createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM chat_sessions ORDER BY start_time DESC")
            while (rs.next()) {
                result.add(rs.toChatSession())
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
                        id = id,
                        chatSessionId = chatSessionId,
                        turnId = turnId,
                        startTime = startTime,
                        endTime = endTime,
                        status = status,
                        prompt = rs.getString("prompt"),
                        response = rs.getString("response"),
                        replyLength = rs.getInt("reply_length"),
                    ))
                    "tool_call" -> result.add(SessionEntry.ToolCall(
                        id = id,
                        chatSessionId = chatSessionId,
                        turnId = turnId,
                        startTime = startTime,
                        endTime = endTime,
                        status = status,
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
                        id = id,
                        chatSessionId = chatSessionId,
                        turnId = turnId,
                        startTime = startTime,
                        endTime = endTime,
                        status = status,
                        title = rs.getString("title"),
                        description = rs.getString("description"),
                    ))
                }
            }
        }
        return result
    }

    // -- Event handling --

    private fun handleEvent(sessionId: String, event: SilentChatEvent) {
        when (event) {
            is SilentChatEvent.SessionReady -> onSessionReady(event)
            is SilentChatEvent.TurnIdSync -> onTurnIdSync(sessionId, event)
            is SilentChatEvent.Reply -> onReply(sessionId, event)
            is SilentChatEvent.ToolCallUpdate -> onToolCallUpdate(event)
            is SilentChatEvent.Steps -> onSteps(sessionId, event)
            is SilentChatEvent.Complete -> onComplete(sessionId, event)
            is SilentChatEvent.Error -> onError(sessionId, event)
            is SilentChatEvent.Cancel -> onCancel(sessionId, event)
            else -> {}
        }
    }

    private fun onSessionReady(event: SilentChatEvent.SessionReady) {
        lastPrompt.remove(PENDING_PROMPT_KEY)?.let { prompt ->
            lastPrompt[event.sessionId] = prompt
        }

        // Upsert: update if exists, insert if not
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

    private fun onTurnIdSync(sessionId: String, event: SilentChatEvent.TurnIdSync) {
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

    private fun onReply(sessionId: String, event: SilentChatEvent.Reply) {
        val turnId = currentTurnId[sessionId] ?: event.parentTurnId ?: return
        db().prepareStatement("UPDATE session_entries SET response = ?, reply_length = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, event.accumulated)
            stmt.setInt(2, event.accumulated.length)
            stmt.setString(3, turnId)
            stmt.executeUpdate()
        }
    }

    private fun onToolCallUpdate(event: SilentChatEvent.ToolCallUpdate) {
        val toolCallId = event.toolCallId ?: return
        val exists = db().prepareStatement("SELECT 1 FROM session_entries WHERE id = ?").use { stmt ->
            stmt.setString(1, toolCallId)
            stmt.executeQuery().next()
        }

        val inputJson = event.input?.let { gson.toJson(it) }
        val outputJson = event.result?.let { gson.toJson(it) }
        val turnId = event.turnId ?: currentTurnId[event.sessionId]

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
                stmt.setString(4, outputJson)
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
                stmt.setString(2, event.sessionId)
                stmt.setString(3, "tool_call")
                stmt.setString(4, turnId)
                stmt.setLong(5, event.timestamp)
                stmt.setString(6, event.status ?: "running")
                stmt.setString(7, event.toolName)
                stmt.setString(8, event.toolType)
                stmt.setString(9, inputJson)
                stmt.setString(10, event.inputMessage)
                stmt.setString(11, outputJson)
                stmt.setString(12, event.error)
                stmt.setString(13, event.progressMessage)
                stmt.setInt(14, event.roundId)
                stmt.executeUpdate()
            }
        }
    }

    private fun onSteps(sessionId: String, event: SilentChatEvent.Steps) {
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

    private fun onComplete(sessionId: String, event: SilentChatEvent.Complete) {
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

    private fun onError(sessionId: String, event: SilentChatEvent.Error) {
        db().prepareStatement("UPDATE chat_sessions SET status = ?, end_time = ? WHERE session_id = ?").use { stmt ->
            stmt.setString(1, SessionStatus.ERROR.name)
            stmt.setLong(2, event.timestamp)
            stmt.setString(3, sessionId)
            stmt.executeUpdate()
        }
        publishStatus(sessionId, SessionStatus.ERROR)
        cleanupSessionMaps(sessionId)
    }

    private fun onCancel(sessionId: String, event: SilentChatEvent.Cancel) {
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
