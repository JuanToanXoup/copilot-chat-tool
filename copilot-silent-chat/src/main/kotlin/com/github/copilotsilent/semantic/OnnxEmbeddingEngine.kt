package com.github.copilotsilent.semantic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * ONNX Runtime-based embedding engine.
 * Loads a sentence-transformer ONNX model and produces embeddings from text input.
 * Uses WordPiece tokenization, mean pooling, and L2 normalization.
 */
class OnnxEmbeddingEngine(
    modelPath: String,
    vocabPath: String
) : AutoCloseable {

    private val log = Logger.getInstance(OnnxEmbeddingEngine::class.java)
    private val lock = ReentrantLock()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val vocab: Map<String, Int>
    private val maxSeqLength = 512

    // Special token IDs
    private val clsTokenId: Int
    private val sepTokenId: Int
    private val unkTokenId: Int
    private val padTokenId: Int

    init {
        session = env.createSession(modelPath, OrtSession.SessionOptions())
        vocab = loadVocab(vocabPath)

        clsTokenId = vocab["[CLS]"] ?: 101
        sepTokenId = vocab["[SEP]"] ?: 102
        unkTokenId = vocab["[UNK]"] ?: 100
        padTokenId = vocab["[PAD]"] ?: 0

        log.info("ONNX embedding engine loaded: model=$modelPath, vocab size=${vocab.size}")
    }

    /**
     * Generate an embedding vector for the given text.
     */
    fun embed(text: String): FloatArray = lock.withLock {
        val tokenIds = tokenize(text)
        val inputIds = LongArray(tokenIds.size) { tokenIds[it].toLong() }
        val attentionMask = LongArray(tokenIds.size) { if (tokenIds[it] != padTokenId) 1L else 0L }
        val tokenTypeIds = LongArray(tokenIds.size) { 0L }

        val shape = longArrayOf(1, tokenIds.size.toLong())

        val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape)
        try {
            val attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)
            try {
                val tokenTypeIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape)
                try {
                    val inputs = mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor,
                        "token_type_ids" to tokenTypeIdsTensor
                    )

                    val results = session.run(inputs)
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val output = results.get(0).value as Array<Array<FloatArray>>
                        val hiddenStates = output[0]
                        val hiddenDim = hiddenStates[0].size

                        // Mean pooling over non-padding tokens
                        val pooled = FloatArray(hiddenDim)
                        var count = 0
                        for (i in tokenIds.indices) {
                            if (attentionMask[i] == 1L) {
                                for (j in 0 until hiddenDim) {
                                    pooled[j] += hiddenStates[i][j]
                                }
                                count++
                            }
                        }
                        if (count > 0) {
                            for (j in pooled.indices) {
                                pooled[j] /= count
                            }
                        }

                        l2Normalize(pooled)
                        pooled
                    } finally {
                        results.close()
                    }
                } finally {
                    tokenTypeIdsTensor.close()
                }
            } finally {
                attentionMaskTensor.close()
            }
        } finally {
            inputIdsTensor.close()
        }
    }

    fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    private fun tokenize(text: String): List<Int> {
        val tokens = mutableListOf(clsTokenId)

        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (word in words) {
            val subTokens = wordPieceTokenize(word)
            if (tokens.size + subTokens.size + 1 > maxSeqLength) break
            tokens.addAll(subTokens)
        }

        tokens.add(sepTokenId)
        return tokens
    }

    private fun wordPieceTokenize(word: String): List<Int> {
        val result = mutableListOf<Int>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var found = false

            while (start < end) {
                val substr = if (start == 0) word.substring(start, end) else "##${word.substring(start, end)}"
                val id = vocab[substr]
                if (id != null) {
                    result.add(id)
                    found = true
                    start = end
                    break
                }
                end--
            }

            if (!found) {
                result.add(unkTokenId)
                start++
            }
        }

        return result
    }

    private fun loadVocab(path: String): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        File(path).bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                map[line.trim()] = index
            }
        }
        return map
    }

    private fun l2Normalize(vec: FloatArray) {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm > 0f) {
            for (i in vec.indices) vec[i] /= norm
        }
    }

    override fun close() {
        session.close()
        env.close()
    }
}
