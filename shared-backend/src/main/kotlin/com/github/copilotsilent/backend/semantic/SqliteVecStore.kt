package com.github.copilotsilent.backend.semantic

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock

/**
 * Vector store backed by sqlite-vec.
 *
 * Uses the vec0 virtual table for native KNN search. The sqlite-vec loadable
 * extension is extracted from bundled resources and loaded into the JDBC connection.
 *
 * Platform-independent — uses java.util.logging instead of IntelliJ Logger.
 */
class SqliteVecStore(
    dbPath: String,
    private val embeddingDim: Int = 384
) : AutoCloseable {

    private val log = Logger.getLogger(SqliteVecStore::class.java.name)
    private val lock = ReentrantLock()
    private val connection: Connection

    init {
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("sqlite-jdbc driver not found on classpath", e)
        }

        val config = org.sqlite.SQLiteConfig()
        config.enableLoadExtension(true)

        connection = org.sqlite.JDBC.createConnection("jdbc:sqlite:$dbPath", config.toProperties())
        connection.autoCommit = true

        loadVecExtension()
        initSchema()
    }

    private fun loadVecExtension() {
        val extPath = extractNativeLib()
        val pathWithoutExt = extPath.substringBeforeLast(".")
        connection.prepareStatement("SELECT load_extension(?)").use { stmt ->
            stmt.setString(1, pathWithoutExt)
            stmt.execute()
        }
        connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT vec_version()")
            if (rs.next()) {
                log.info("sqlite-vec loaded: ${rs.getString(1)}")
            }
        }
    }

    private fun initSchema() {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_path TEXT NOT NULL,
                    start_line INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    hash TEXT NOT NULL
                )
            """)
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chunks_file_path ON chunks(file_path)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chunks_hash ON chunks(hash)")
            stmt.executeUpdate("""
                CREATE VIRTUAL TABLE IF NOT EXISTS vec_chunks USING vec0(
                    chunk_id INTEGER PRIMARY KEY,
                    embedding float[$embeddingDim]
                )
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS indexed_files (
                    file_path TEXT PRIMARY KEY,
                    timestamp INTEGER NOT NULL
                )
            """)

            try {
                connection.prepareStatement("SELECT timestamp FROM indexed_files LIMIT 1").use { it.executeQuery() }
            } catch (_: Exception) {
                log.info("Migrating indexed_files table to timestamp schema")
                stmt.executeUpdate("DROP TABLE indexed_files")
                stmt.executeUpdate("""
                    CREATE TABLE indexed_files (
                        file_path TEXT PRIMARY KEY,
                        timestamp INTEGER NOT NULL
                    )
                """)
            }
        }
    }

    fun upsertChunk(
        filePath: String, startLine: Int, endLine: Int,
        text: String, hash: String, embedding: FloatArray
    ): Long = lock.withLock {
        val existing = connection.prepareStatement(
            "SELECT id FROM chunks WHERE file_path = ? AND start_line = ? AND hash = ?"
        ).use { stmt ->
            stmt.setString(1, filePath)
            stmt.setInt(2, startLine)
            stmt.setString(3, hash)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getLong("id") else null
        }
        if (existing != null) return existing

        val chunkId = connection.prepareStatement(
            "INSERT INTO chunks (file_path, start_line, end_line, text, hash) VALUES (?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        ).use { stmt ->
            stmt.setString(1, filePath)
            stmt.setInt(2, startLine)
            stmt.setInt(3, endLine)
            stmt.setString(4, text)
            stmt.setString(5, hash)
            stmt.executeUpdate()
            val keys = stmt.generatedKeys
            keys.next()
            keys.getLong(1)
        }

        connection.prepareStatement(
            "INSERT INTO vec_chunks(chunk_id, embedding) VALUES (?, ?)"
        ).use { stmt ->
            stmt.setLong(1, chunkId)
            stmt.setBytes(2, floatArrayToBytes(embedding))
            stmt.executeUpdate()
        }

        chunkId
    }

    fun search(queryEmbedding: FloatArray, limit: Int = 10): List<SearchResult> = lock.withLock {
        val results = mutableListOf<SearchResult>()
        connection.prepareStatement("""
            SELECT v.chunk_id, v.distance, c.file_path, c.start_line, c.end_line, c.text
            FROM (
                SELECT chunk_id, distance
                FROM vec_chunks
                WHERE embedding MATCH ?
                  AND k = ?
                ORDER BY distance
            ) v
            JOIN chunks c ON c.id = v.chunk_id
        """).use { stmt ->
            stmt.setBytes(1, floatArrayToBytes(queryEmbedding))
            stmt.setInt(2, limit)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                results.add(SearchResult(
                    filePath = rs.getString("file_path"),
                    startLine = rs.getInt("start_line"),
                    endLine = rs.getInt("end_line"),
                    text = rs.getString("text"),
                    distance = rs.getFloat("distance")
                ))
            }
        }
        results
    }

    fun deleteChunksForFile(filePath: String) = lock.withLock {
        connection.prepareStatement(
            "DELETE FROM vec_chunks WHERE chunk_id IN (SELECT id FROM chunks WHERE file_path = ?)"
        ).use { stmt -> stmt.setString(1, filePath); stmt.executeUpdate() }
        connection.prepareStatement("DELETE FROM chunks WHERE file_path = ?").use { stmt ->
            stmt.setString(1, filePath); stmt.executeUpdate()
        }
    }

    fun getIndexedFileTimestamps(): Map<String, Long> = lock.withLock {
        val result = mutableMapOf<String, Long>()
        connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT file_path, timestamp FROM indexed_files")
            while (rs.next()) { result[rs.getString("file_path")] = rs.getLong("timestamp") }
        }
        result
    }

    fun setFileTimestamp(filePath: String, timestamp: Long) = lock.withLock {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO indexed_files (file_path, timestamp) VALUES (?, ?)"
        ).use { stmt -> stmt.setString(1, filePath); stmt.setLong(2, timestamp); stmt.executeUpdate() }
    }

    fun removeFile(filePath: String) = lock.withLock {
        deleteChunksForFile(filePath)
        connection.prepareStatement("DELETE FROM indexed_files WHERE file_path = ?").use { stmt ->
            stmt.setString(1, filePath); stmt.executeUpdate()
        }
    }

    fun getChunkCount(): Int = lock.withLock {
        connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT COUNT(*) FROM chunks")
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    fun clear() = lock.withLock {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("DELETE FROM vec_chunks")
            stmt.executeUpdate("DELETE FROM chunks")
            stmt.executeUpdate("DELETE FROM indexed_files")
        }
    }

    override fun close() { connection.close() }

    private fun floatArrayToBytes(arr: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in arr) buf.putFloat(f)
        return buf.array()
    }

    data class SearchResult(
        val filePath: String, val startLine: Int, val endLine: Int,
        val text: String, val distance: Float
    )

    companion object {
        private val extractLock = ReentrantLock()
        private var extractedPath: String? = null

        fun extractNativeLib(): String = extractLock.withLock {
            extractedPath?.let { if (File(it).exists()) return it }

            val osName = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()

            val (libName, ext) = when {
                osName.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) ->
                    "vec0-macos-aarch64" to ".dylib"
                osName.contains("mac") -> "vec0-macos-x86_64" to ".dylib"
                osName.contains("linux") && (arch.contains("aarch64") || arch.contains("arm64")) ->
                    "vec0-linux-aarch64" to ".so"
                osName.contains("linux") -> "vec0-linux-x86_64" to ".so"
                osName.contains("win") -> "vec0-windows-x86_64" to ".dll"
                else -> throw UnsupportedOperationException("Unsupported platform: $osName $arch")
            }

            val resourcePath = "/native/$libName$ext"
            val resource = SqliteVecStore::class.java.getResourceAsStream(resourcePath)
                ?: throw IllegalStateException("sqlite-vec native library not found: $resourcePath")

            val tmpDir = File(System.getProperty("java.io.tmpdir"), "copilot-silent-native")
            tmpDir.mkdirs()
            val tmpFile = File(tmpDir, "vec0$ext")

            if (!tmpFile.exists() || tmpFile.length() == 0L) {
                val staging = File.createTempFile("vec0-", ext, tmpDir)
                try {
                    resource.use { input -> staging.outputStream().use { output -> input.copyTo(output) } }
                    if (!osName.contains("win")) staging.setExecutable(true)
                    Files.move(staging.toPath(), tmpFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    staging.delete()
                    if (!tmpFile.exists() || tmpFile.length() == 0L) throw e
                }
            } else {
                resource.close()
            }

            extractedPath = tmpFile.absolutePath
            Logger.getLogger(SqliteVecStore::class.java.name)
                .info("sqlite-vec native lib extracted to: ${tmpFile.absolutePath}")
            tmpFile.absolutePath
        }
    }
}
