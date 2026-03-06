package com.github.copilotsilent.store

import com.github.copilotsilent.model.ChatStatusListener
import com.github.copilotsilent.model.SilentChatEvent
import com.github.copilotsilent.model.SilentChatListener
import com.github.copilotsilent.store.db.ChatSessions
import com.github.copilotsilent.store.db.DatabaseManager
import com.github.copilotsilent.store.db.PlaybookRuns
import com.github.copilotsilent.store.db.SessionEntries
import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Event-sourced session store. Subscribes to [SilentChatListener.TOPIC]
 * and persists [PlaybookRun] / [ChatSession] / [SessionEntry] state to
 * ~/.citi-ai/projects/{project-slug}/sessions.db via Exposed ORM.
 */
@Service(Service.Level.PROJECT)
class SessionStore(private val project: Project) {

    companion object {
        const val PENDING_PROMPT_KEY = "__pending__"
    }

    private val log = Logger.getInstance(SessionStore::class.java)
    private val gson = Gson()
    private lateinit var db: Database

    /** Tracks the most recent prompt per session for correlating with replies */
    private val lastPrompt = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** In-memory status cache for fast queries */
    private val liveStatus = java.util.concurrent.ConcurrentHashMap<String, SessionStatus>()

    fun getStatus(sessionId: String): SessionStatus? = liveStatus[sessionId]

    private fun publishStatus(sessionId: String, status: SessionStatus) {
        liveStatus[sessionId] = status
        project.messageBus.syncPublisher(ChatStatusListener.TOPIC)
            .onStatusChanged(sessionId, status)
    }

    fun init() {
        db = DatabaseManager.initSessionsDb(project)

        project.messageBus.connect().subscribe(
            SilentChatListener.TOPIC, object : SilentChatListener {
                override fun onEvent(sessionId: String, event: SilentChatEvent) {
                    try {
                        handleEvent(sessionId, event)
                    } catch (e: Exception) {
                        log.warn("SessionStore failed to handle event: ${event::class.simpleName}", e)
                    }
                }
            }
        )
        log.info("SessionStore initialized with db at ${DatabaseManager.projectDir(project)}")
    }

    // -- Playbook management --

    fun createPlaybook(): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        transaction(db) {
            PlaybookRuns.insert {
                it[PlaybookRuns.id] = id
                it[startTime] = now
            }
        }
        return id
    }

    fun assignSessionToPlaybook(sessionId: String, playbookId: String) {
        transaction(db) {
            ChatSessions.update({ ChatSessions.sessionId eq sessionId }) {
                it[ChatSessions.playbookId] = playbookId
            }
        }
    }

    fun completePlaybook(playbookId: String) {
        transaction(db) {
            PlaybookRuns.update({ PlaybookRuns.id eq playbookId }) {
                it[endTime] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Records the user's prompt for a session so it can be stored
     * with the message entry when the turn starts.
     */
    fun recordPrompt(sessionId: String, prompt: String) {
        lastPrompt[sessionId] = prompt
    }

    // -- Queries --

    fun getPlaybook(id: String): PlaybookRun? = transaction(db) {
        val row = PlaybookRuns.selectAll().where { PlaybookRuns.id eq id }.firstOrNull() ?: return@transaction null
        val sessions = getChatSessionsForPlaybook(id)
        PlaybookRun(
            id = row[PlaybookRuns.id],
            startTime = row[PlaybookRuns.startTime],
            endTime = row[PlaybookRuns.endTime],
            chatSessions = sessions.toMutableList(),
        )
    }

    fun getSession(sessionId: String): ChatSession? = transaction(db) {
        val row = ChatSessions.selectAll().where { ChatSessions.sessionId eq sessionId }.firstOrNull()
            ?: return@transaction null
        val entries = getEntriesForSession(sessionId)
        rowToChatSession(row, entries)
    }

    fun allPlaybooks(): List<PlaybookRun> = transaction(db) {
        PlaybookRuns.selectAll()
            .orderBy(PlaybookRuns.startTime to SortOrder.DESC)
            .map { row ->
                val sessions = getChatSessionsForPlaybook(row[PlaybookRuns.id])
                PlaybookRun(
                    id = row[PlaybookRuns.id],
                    startTime = row[PlaybookRuns.startTime],
                    endTime = row[PlaybookRuns.endTime],
                    chatSessions = sessions.toMutableList(),
                )
            }
    }

    fun allSessions(): List<ChatSession> = transaction(db) {
        ChatSessions.selectAll()
            .orderBy(ChatSessions.startTime to SortOrder.DESC)
            .map { row ->
                val entries = getEntriesForSession(row[ChatSessions.sessionId])
                rowToChatSession(row, entries)
            }
    }

    // -- Internal queries --

    private fun getChatSessionsForPlaybook(playbookId: String): List<ChatSession> {
        return ChatSessions.selectAll().where { ChatSessions.playbookId eq playbookId }
            .orderBy(ChatSessions.startTime to SortOrder.ASC)
            .map { row ->
                val entries = getEntriesForSession(row[ChatSessions.sessionId])
                rowToChatSession(row, entries)
            }
    }

    private fun getEntriesForSession(sessionId: String): List<SessionEntry> {
        return SessionEntries.selectAll().where { SessionEntries.chatSessionId eq sessionId }
            .orderBy(SessionEntries.startTime to SortOrder.ASC)
            .map { rowToEntry(it) }
    }

    private fun rowToChatSession(row: ResultRow, entries: List<SessionEntry>): ChatSession {
        return ChatSession(
            sessionId = row[ChatSessions.sessionId],
            playbookId = row[ChatSessions.playbookId],
            startTime = row[ChatSessions.startTime],
            endTime = row[ChatSessions.endTime],
            status = SessionStatus.valueOf(row[ChatSessions.status]),
            entries = entries.toMutableList(),
        )
    }

    private fun rowToEntry(row: ResultRow): SessionEntry {
        return when (row[SessionEntries.entryType]) {
            "message" -> SessionEntry.Message(
                id = row[SessionEntries.id],
                chatSessionId = row[SessionEntries.chatSessionId],
                startTime = row[SessionEntries.startTime],
                endTime = row[SessionEntries.endTime],
                status = row[SessionEntries.status],
                prompt = row[SessionEntries.prompt],
                response = row[SessionEntries.response],
                replyLength = row[SessionEntries.replyLength] ?: 0,
            )
            "tool_call" -> SessionEntry.ToolCall(
                id = row[SessionEntries.id],
                chatSessionId = row[SessionEntries.chatSessionId],
                startTime = row[SessionEntries.startTime],
                endTime = row[SessionEntries.endTime],
                status = row[SessionEntries.status],
                toolName = row[SessionEntries.toolName],
                toolType = row[SessionEntries.toolType],
                input = row[SessionEntries.input],
                output = row[SessionEntries.output],
                error = row[SessionEntries.error],
                durationFromAgent = row[SessionEntries.durationMs],
            )
            else -> throw IllegalStateException("Unknown entry type: ${row[SessionEntries.entryType]}")
        }
    }

    // -- Event handling --

    private fun handleEvent(sessionId: String, event: SilentChatEvent) {
        when (event) {
            is SilentChatEvent.SessionReady -> onSessionReady(event)
            is SilentChatEvent.TurnIdSync -> onTurnIdSync(sessionId, event)
            is SilentChatEvent.Reply -> onReply(sessionId, event)
            is SilentChatEvent.ToolCallUpdate -> onToolCallUpdate(event)
            is SilentChatEvent.Complete -> onComplete(sessionId, event)
            is SilentChatEvent.Error -> onError(sessionId, event)
            is SilentChatEvent.Cancel -> onCancel(sessionId)
            else -> {}
        }
    }

    private fun onSessionReady(event: SilentChatEvent.SessionReady) {
        // Resolve pending prompt key → real session ID
        lastPrompt.remove(PENDING_PROMPT_KEY)?.let { prompt ->
            lastPrompt[event.sessionId] = prompt
        }

        transaction(db) {
            val exists = ChatSessions.selectAll()
                .where { ChatSessions.sessionId eq event.sessionId }
                .count() > 0

            if (exists) {
                ChatSessions.update({ ChatSessions.sessionId eq event.sessionId }) {
                    it[startTime] = event.timestamp
                    it[status] = SessionStatus.ACTIVE.name
                }
            } else {
                ChatSessions.insert {
                    it[sessionId] = event.sessionId
                    it[startTime] = event.timestamp
                    it[status] = SessionStatus.ACTIVE.name
                }
            }
        }
        publishStatus(event.sessionId, SessionStatus.ACTIVE)
    }

    private fun onTurnIdSync(sessionId: String, event: SilentChatEvent.TurnIdSync) {
        transaction(db) {
            val exists = SessionEntries.selectAll()
                .where { SessionEntries.id eq event.turnId }
                .count() > 0

            if (!exists) {
                SessionEntries.insert {
                    it[id] = event.turnId
                    it[chatSessionId] = sessionId
                    it[entryType] = "message"
                    it[startTime] = event.timestamp
                    it[status] = "streaming"
                    it[prompt] = lastPrompt.remove(sessionId)
                }
            }
        }
    }

    private fun onReply(sessionId: String, event: SilentChatEvent.Reply) {
        val turnId = event.parentTurnId ?: return
        transaction(db) {
            SessionEntries.update({ SessionEntries.id eq turnId }) {
                it[response] = event.accumulated
                it[replyLength] = event.accumulated.length
            }
        }
    }

    private fun onToolCallUpdate(event: SilentChatEvent.ToolCallUpdate) {
        val toolCallId = event.toolCallId ?: return
        transaction(db) {
            val exists = SessionEntries.selectAll()
                .where { SessionEntries.id eq toolCallId }
                .count() > 0

            val inputJson = event.input?.let { gson.toJson(it) }
            val outputJson = event.result?.let { gson.toJson(it) }

            if (exists) {
                SessionEntries.update({ SessionEntries.id eq toolCallId }) {
                    it[status] = event.status ?: "unknown"
                    it[error] = event.error
                    it[durationMs] = event.durationMs
                    if (outputJson != null) it[output] = outputJson
                    if (event.status != "running") {
                        it[endTime] = event.timestamp
                    }
                }
            } else {
                SessionEntries.insert {
                    it[id] = toolCallId
                    it[chatSessionId] = event.sessionId
                    it[entryType] = "tool_call"
                    it[startTime] = event.timestamp
                    it[status] = event.status ?: "running"
                    it[toolName] = event.toolName
                    it[toolType] = event.toolType
                    it[input] = inputJson
                    it[output] = outputJson
                    it[error] = event.error
                }
            }
        }
    }

    private fun onComplete(sessionId: String, event: SilentChatEvent.Complete) {
        transaction(db) {
            ChatSessions.update({ ChatSessions.sessionId eq sessionId }) {
                it[status] = SessionStatus.COMPLETED.name
                it[endTime] = event.timestamp
            }

            SessionEntries.update({
                (SessionEntries.chatSessionId eq sessionId) and
                    (SessionEntries.entryType eq "message") and
                    SessionEntries.endTime.isNull()
            }) {
                it[endTime] = event.timestamp
                it[status] = "complete"
            }
        }
        publishStatus(sessionId, SessionStatus.COMPLETED)
    }

    private fun onError(sessionId: String, event: SilentChatEvent.Error) {
        transaction(db) {
            ChatSessions.update({ ChatSessions.sessionId eq sessionId }) {
                it[status] = SessionStatus.ERROR.name
                it[endTime] = event.timestamp
            }
        }
        publishStatus(sessionId, SessionStatus.ERROR)
    }

    private fun onCancel(sessionId: String) {
        val now = System.currentTimeMillis()
        transaction(db) {
            ChatSessions.update({ ChatSessions.sessionId eq sessionId }) {
                it[status] = SessionStatus.CANCELLED.name
                it[endTime] = now
            }
        }
        publishStatus(sessionId, SessionStatus.CANCELLED)
    }
}
