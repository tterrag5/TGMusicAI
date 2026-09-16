package com.example.tgmusicai.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Wraps a quantized MiniLM (all-MiniLM-L6-v2) ONNX model to turn lyrics text into a 384-dim
 * sentence embedding, usable for similarity-based grouping ("Start Radio" by lyrical theme, mood
 * clustering, etc). Lazily and defensively initialized: any failure to load the model/tokenizer
 * permanently (for this process) marks the engine unavailable rather than retrying or throwing --
 * every public function returns [AiModelResult] instead of propagating exceptions.
 */
class LyricsEmbeddingEngine(private val context: Context) {

    companion object {
        private const val TAG = "LyricsEmbeddingEngine"
        private const val MODEL_ASSET = "ai/minilm_quantized.onnx"
        private const val VOCAB_ASSET = "ai/vocab.txt"
        private const val MAX_SEQ_LEN = 128
        const val EMBEDDING_DIM = 384

        /** Cosine similarity between two embeddings of the same dimension produced by [embed]. Returns 0f on mismatch. */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            // Both vectors are already L2-normalized by embed(), so the dot product IS the cosine similarity.
            return dot
        }
    }

    @Volatile private var initFailed = false
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private val initLock = Any()

    val isAvailable: Boolean get() = session != null && tokenizer != null

    private fun ensureInitialized(): Boolean {
        if (isAvailable) return true
        if (initFailed) return false
        synchronized(initLock) {
            if (isAvailable) return true
            if (initFailed) return false
            try {
                val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                session = env.createSession(modelBytes, OrtSession.SessionOptions())
                tokenizer = WordPieceTokenizer.fromAsset(context, VOCAB_ASSET)
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize lyrics embedding engine -- feature will stay disabled", e)
                initFailed = true
                session = null
                tokenizer = null
                false
            }
        }
        return isAvailable
    }

    /** Embeds [lyricsText] into a 384-dim, L2-normalized sentence embedding. */
    fun embed(lyricsText: String): AiModelResult<FloatArray> {
        if (lyricsText.isBlank()) return AiModelResult.Unavailable("blank lyrics text")
        if (!ensureInitialized()) return AiModelResult.Unavailable("model unavailable")
        val ortSession = session ?: return AiModelResult.Unavailable("model unavailable")
        val wordPieceTokenizer = tokenizer ?: return AiModelResult.Unavailable("tokenizer unavailable")

        return try {
            val ids = wordPieceTokenizer.encode(lyricsText, MAX_SEQ_LEN)
            val mask = wordPieceTokenizer.attentionMask(ids)
            val env = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(1, ids.size.toLong())

            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape).use { inputIds ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(mask.size) { mask[it].toLong() }), shape).use { attentionMask ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape).use { tokenTypeIds ->
                        val inputs = mapOf(
                            "input_ids" to inputIds,
                            "attention_mask" to attentionMask,
                            "token_type_ids" to tokenTypeIds
                        )
                        ortSession.run(inputs).use { results ->
                            val hiddenState = results.get("last_hidden_state").orElse(null) as? OnnxTensor
                                ?: return AiModelResult.Error(IllegalStateException("last_hidden_state missing from model output"))
                            val flat = hiddenState.floatBuffer
                            AiModelResult.Success(meanPoolAndNormalize(flat, mask))
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Lyrics embedding inference failed for this call", e)
            AiModelResult.Error(e)
        }
    }

    /** Mean-pools token embeddings (weighted by [attentionMask], skipping padding) then L2-normalizes. */
    private fun meanPoolAndNormalize(flatHiddenState: java.nio.FloatBuffer, attentionMask: IntArray): FloatArray {
        val pooled = FloatArray(EMBEDDING_DIM)
        var validTokens = 0
        for (tokenIndex in attentionMask.indices) {
            if (attentionMask[tokenIndex] == 0) continue
            validTokens++
            val base = tokenIndex * EMBEDDING_DIM
            for (dim in 0 until EMBEDDING_DIM) {
                pooled[dim] += flatHiddenState.get(base + dim)
            }
        }
        if (validTokens > 0) {
            for (dim in 0 until EMBEDDING_DIM) pooled[dim] /= validTokens
        }
        var norm = 0f
        for (v in pooled) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-8f) {
            for (dim in pooled.indices) pooled[dim] /= norm
        }
        return pooled
    }

    fun release() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ONNX session", e)
        }
        session = null
        tokenizer = null
    }
}
