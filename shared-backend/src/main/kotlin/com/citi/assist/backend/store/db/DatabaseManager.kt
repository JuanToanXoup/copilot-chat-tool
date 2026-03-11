package com.citi.assist.backend.store.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Manages SQLite database connections at ~/.citi-ai/projects/{project-slug}/.
 *
 * Platform-independent — takes a project slug string instead of an IDE Project object.
 * Uses raw JDBC to avoid classloader conflicts.
 */
object DatabaseManager {

    private val log = Logger.getLogger(DatabaseManager::class.java.name)
    private val sessionsConns = ConcurrentHashMap<String, Connection>()

    /**
     * Returns the project data directory: ~/.citi-ai/projects/{project-slug}/
     */
    fun projectDir(projectSlug: String): File {
        val slug = projectSlug
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        val dir = File(System.getProperty("user.home"), ".citi-ai/projects/$slug")
        dir.mkdirs()
        return dir
    }

    /**
     * Returns a raw JDBC connection to sessions.db, creating tables if needed.
     */
    fun getSessionsConnection(projectSlug: String): Connection {
        val dbFile = File(projectDir(projectSlug), "sessions.db")
        val key = dbFile.absolutePath

        sessionsConns[key]?.let { if (!it.isClosed) return it }
        log.info("Initializing sessions.db at ${dbFile.absolutePath}")

        if (dbFile.exists() && dbFile.length() == 0L) {
            log.warning("Deleting empty sessions.db file from previous failed init")
            dbFile.delete()
        }

        try {
            Class.forName("org.sqlite.JDBC")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("sqlite-jdbc driver not found on classpath", e)
        }

        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.autoCommit = true

        conn.createStatement().use { stmt ->
            stmt.executeUpdate("PRAGMA journal_mode=WAL;")
            stmt.executeUpdate("PRAGMA foreign_keys=ON;")

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS playbook_runs (
                    id TEXT PRIMARY KEY,
                    start_time INTEGER NOT NULL,
                    end_time INTEGER
                )
            """)

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS chat_sessions (
                    session_id TEXT PRIMARY KEY,
                    playbook_id TEXT REFERENCES playbook_runs(id),
                    start_time INTEGER NOT NULL,
                    end_time INTEGER,
                    status TEXT NOT NULL DEFAULT 'ACTIVE'
                )
            """)

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS session_entries (
                    id TEXT PRIMARY KEY,
                    chat_session_id TEXT NOT NULL REFERENCES chat_sessions(session_id),
                    entry_type TEXT NOT NULL,
                    turn_id TEXT,
                    start_time INTEGER NOT NULL,
                    end_time INTEGER,
                    status TEXT NOT NULL DEFAULT 'running',
                    duration_ms INTEGER,
                    prompt TEXT,
                    response TEXT,
                    reply_length INTEGER,
                    tool_name TEXT,
                    tool_type TEXT,
                    input TEXT,
                    input_message TEXT,
                    output TEXT,
                    error TEXT,
                    progress_message TEXT,
                    round_id INTEGER,
                    title TEXT,
                    description TEXT
                )
            """)

            val migrationColumns = listOf(
                "turn_id TEXT",
                "input_message TEXT",
                "progress_message TEXT",
                "round_id INTEGER",
                "title TEXT",
                "description TEXT",
            )
            for (col in migrationColumns) {
                try {
                    stmt.executeUpdate("ALTER TABLE session_entries ADD COLUMN $col")
                } catch (_: Exception) { }
            }
        }

        log.info("sessions.db initialized successfully")
        sessionsConns[key] = conn
        return conn
    }

    /**
     * Returns the path for vectors.db (raw JDBC + sqlite-vec).
     */
    fun vectorsDbPath(projectSlug: String): File {
        return File(projectDir(projectSlug), "vectors.db")
    }
}
