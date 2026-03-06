package com.github.copilotsilent.semantic

import com.github.copilotsilent.store.db.DatabaseManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the vector index for the project.
 * Walks source files via FilenameIndex, chunks by method (PSI visitor),
 * embeds each chunk, and upserts into SqliteVecStore.
 * Registers a VirtualFileListener for re-indexing on save.
 */
@Service(Service.Level.PROJECT)
class IndexManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(IndexManager::class.java)
    private val indexing = AtomicBoolean(false)
    private val totalFiles = AtomicInteger(0)
    private val processedFiles = AtomicInteger(0)

    @Volatile private var store: SqliteVecStore? = null
    @Volatile private var engine: OnnxEmbeddingEngine? = null

    fun indexProject() {
        if (!indexing.compareAndSet(false, true)) {
            log.info("Indexing already in progress, skipping")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ensureInitialized()
                performIncrementalIndex()
                registerFileWatcher()
                log.info("Project indexing complete")
            } catch (e: Exception) {
                log.warn("Project indexing failed", e)
            } finally {
                indexing.set(false)
            }
        }
    }

    private fun ensureInitialized() {
        if (store != null && engine != null) return

        val embeddingDim = 384

        // Try bundled model from plugin resources
        val bundledModel = javaClass.getResourceAsStream("/models/all-MiniLM-L6-v2.onnx")
        val bundledVocab = javaClass.getResourceAsStream("/models/vocab.txt")

        if (bundledModel == null || bundledVocab == null) {
            throw IllegalStateException(
                "ONNX model not found in plugin resources. " +
                        "Place all-MiniLM-L6-v2.onnx and vocab.txt in src/main/resources/models/"
            )
        }

        val tmpDir = File(System.getProperty("java.io.tmpdir"), "copilot-silent-models")
        tmpDir.mkdirs()

        val tmpModel = File(tmpDir, "all-MiniLM-L6-v2.onnx")
        val tmpVocab = File(tmpDir, "vocab.txt")

        if (!tmpModel.exists()) {
            bundledModel.use { input -> tmpModel.outputStream().use { output -> input.copyTo(output) } }
        } else {
            bundledModel.close()
        }
        if (!tmpVocab.exists()) {
            bundledVocab.use { input -> tmpVocab.outputStream().use { output -> input.copyTo(output) } }
        } else {
            bundledVocab.close()
        }

        engine = OnnxEmbeddingEngine(tmpModel.absolutePath, tmpVocab.absolutePath)

        val dbFile = DatabaseManager.vectorsDbPath(project)
        try {
            store = SqliteVecStore(dbFile.absolutePath, embeddingDim)
        } catch (e: Exception) {
            log.warn("SqliteVecStore init failed, recreating DB: ${e.message}")
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
            store = SqliteVecStore(dbFile.absolutePath, embeddingDim)
        }
    }

    private fun performIncrementalIndex() {
        val currentStore = store ?: return
        val currentEngine = engine ?: return
        val basePath = project.basePath ?: return

        log.info("Starting incremental project index...")

        val sourceScope = GlobalSearchScope.projectScope(project)

        val javaFiles = ReadAction.compute<Collection<VirtualFile>, Exception> {
            FilenameIndex.getAllFilesByExt(project, "java", sourceScope)
        }

        val kotlinFiles = ReadAction.compute<Collection<VirtualFile>, Exception> {
            FilenameIndex.getAllFilesByExt(project, "kt", sourceScope)
        }

        val allFiles = (javaFiles + kotlinFiles).filter { it.path.startsWith(basePath) }
        val currentFilePaths = mutableSetOf<String>()
        for (file in allFiles) {
            currentFilePaths.add(file.path.removePrefix(basePath).removePrefix("/"))
        }

        val previousTimestamps = currentStore.getIndexedFileTimestamps()

        val filesToIndex = mutableListOf<VirtualFile>()
        var skipped = 0

        for (file in allFiles) {
            val relativePath = file.path.removePrefix(basePath).removePrefix("/")
            val currentTimestamp = file.timeStamp
            val previousTimestamp = previousTimestamps[relativePath]
            if (previousTimestamp != null && previousTimestamp == currentTimestamp) {
                skipped++
            } else {
                filesToIndex.add(file)
            }
        }

        val removedFiles = previousTimestamps.keys - currentFilePaths
        for (removedPath in removedFiles) {
            currentStore.removeFile(removedPath)
        }
        if (removedFiles.isNotEmpty()) {
            log.info("Removed ${removedFiles.size} deleted files from index")
        }

        totalFiles.set(filesToIndex.size)
        processedFiles.set(0)
        log.info("Incremental index: ${filesToIndex.size} changed, $skipped unchanged, ${removedFiles.size} removed")

        if (filesToIndex.isEmpty()) {
            log.info("Index is up to date, nothing to re-embed")
            return
        }

        var chunksIndexed = 0
        for (file in filesToIndex) {
            val relativePath = file.path.removePrefix(basePath).removePrefix("/")

            currentStore.deleteChunksForFile(relativePath)

            val chunks = extractChunks(file, basePath)
            for (chunk in chunks) {
                val embedding = currentEngine.embed(chunk.text)
                currentStore.upsertChunk(
                    filePath = chunk.filePath,
                    startLine = chunk.startLine,
                    endLine = chunk.endLine,
                    text = chunk.text,
                    hash = sha256(chunk.text),
                    embedding = embedding
                )
                chunksIndexed++
            }

            currentStore.setFileTimestamp(relativePath, file.timeStamp)

            val done = processedFiles.incrementAndGet()
            if (done % 50 == 0) {
                log.info("Indexing progress: $done/${filesToIndex.size} files, $chunksIndexed chunks")
            }
        }
        log.info("Indexing complete: $chunksIndexed chunks from ${filesToIndex.size} changed files ($skipped unchanged)")
    }

    private fun extractChunks(file: VirtualFile, basePath: String): List<CodeChunk> {
        return ReadAction.compute<List<CodeChunk>, Exception> {
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@compute emptyList()
            val relativePath = file.path.removePrefix(basePath).removePrefix("/")
            val chunks = mutableListOf<CodeChunk>()

            if (psiFile is PsiJavaFile) {
                psiFile.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitMethod(method: PsiMethod) {
                        val doc = psiFile.viewProvider.document ?: return
                        val startLine = doc.getLineNumber(method.textRange.startOffset) + 1
                        val endLine = doc.getLineNumber(method.textRange.endOffset) + 1
                        val text = method.text
                        if (text.length > 20) {
                            chunks.add(CodeChunk(relativePath, startLine, endLine, text))
                        }
                    }
                })

                if (chunks.isEmpty() && psiFile.text.length > 20) {
                    chunks.add(CodeChunk(relativePath, 1, psiFile.text.lines().size, psiFile.text))
                }
            } else {
                if (psiFile.text.length > 20) {
                    val lines = psiFile.text.lines()
                    val chunkSize = 500
                    var i = 0
                    while (i < lines.size) {
                        val end = minOf(i + chunkSize, lines.size)
                        val chunkText = lines.subList(i, end).joinToString("\n")
                        if (chunkText.length > 20) {
                            chunks.add(CodeChunk(relativePath, i + 1, end, chunkText))
                        }
                        i = end
                    }
                }
            }

            chunks
        }
    }

    fun reindexFile(file: VirtualFile) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val currentStore = store ?: return@executeOnPooledThread
                val currentEngine = engine ?: return@executeOnPooledThread
                val basePath = project.basePath ?: return@executeOnPooledThread

                val relativePath = file.path.removePrefix(basePath).removePrefix("/")
                currentStore.deleteChunksForFile(relativePath)

                val chunks = extractChunks(file, basePath)
                for (chunk in chunks) {
                    val embedding = currentEngine.embed(chunk.text)
                    currentStore.upsertChunk(
                        filePath = chunk.filePath,
                        startLine = chunk.startLine,
                        endLine = chunk.endLine,
                        text = chunk.text,
                        hash = sha256(chunk.text),
                        embedding = embedding
                    )
                }

                currentStore.setFileTimestamp(relativePath, file.timeStamp)

                log.info("Re-indexed ${chunks.size} chunks for $relativePath")
            } catch (e: Exception) {
                log.warn("Failed to re-index file: ${file.path}", e)
            }
        }
    }

    private fun registerFileWatcher() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    for (event in events) {
                        if (event !is VFileContentChangeEvent) continue
                        val file = event.file
                        if (file.extension in listOf("java", "kt") && isInProject(file)) {
                            reindexFile(file)
                        }
                    }
                }
            }
        )
    }

    private fun isInProject(file: VirtualFile): Boolean {
        val basePath = project.basePath ?: return false
        return file.path.startsWith(basePath)
    }

    fun getStore(): SqliteVecStore? = store
    fun getEngine(): OnnxEmbeddingEngine? = engine
    fun isIndexing(): Boolean = indexing.get()

    fun getIndexingProgressPercent(): Int {
        if (!indexing.get()) return -1
        val total = totalFiles.get()
        if (total == 0) return 0
        return ((processedFiles.get().toLong() * 100) / total).toInt().coerceIn(0, 100)
    }

    override fun dispose() {
        store?.close()
        engine?.close()
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private data class CodeChunk(
        val filePath: String,
        val startLine: Int,
        val endLine: Int,
        val text: String
    )
}
