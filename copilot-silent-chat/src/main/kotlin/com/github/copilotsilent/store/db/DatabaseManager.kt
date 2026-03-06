package com.github.copilotsilent.store.db

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection

/**
 * Manages SQLite database connections at ~/.citi-ai/projects/{project-slug}/.
 *
 * Creates the directory structure on first use and initializes schema.
 */
object DatabaseManager {

    private val log = Logger.getInstance(DatabaseManager::class.java)

    private var sessionsDb: Database? = null

    /**
     * Returns the project data directory: ~/.citi-ai/projects/{project-slug}/
     */
    fun projectDir(project: Project): File {
        val slug = project.name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        val dir = File(System.getProperty("user.home"), ".citi-ai/projects/$slug")
        dir.mkdirs()
        return dir
    }

    /**
     * Connects to sessions.db and creates tables if needed.
     */
    fun initSessionsDb(project: Project): Database {
        sessionsDb?.let { return it }

        val dbFile = File(projectDir(project), "sessions.db")
        log.info("Initializing sessions.db at ${dbFile.absolutePath}")

        val db = Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )

        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        transaction(db) {
            exec("PRAGMA journal_mode=WAL;")
            exec("PRAGMA foreign_keys=ON;")
            SchemaUtils.create(PlaybookRuns, ChatSessions, SessionEntries)
        }

        sessionsDb = db
        return db
    }

    /**
     * Returns the path for vectors.db (raw JDBC + sqlite-vec).
     * Does not connect — the vector store manages its own connection.
     */
    fun vectorsDbPath(project: Project): File {
        return File(projectDir(project), "vectors.db")
    }

    fun getSessionsDb(): Database? = sessionsDb
}
