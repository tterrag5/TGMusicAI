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

        /**
         * Words per embedding window. Comfortably under [MAX_SEQ_LEN] wordpiece tokens, since
         * wordpiece splits uncommon words -- which song lyrics are full of -- into several tokens.
         */
        private const val WINDOW_WORDS = 70

        /** Overlap between windows, so a phrase crossing a boundary survives whole in one of them. */
        private const val WINDOW_OVERLAP_WORDS = 15

        /** Cap on windows per song, so a transcript of an hour-long mix cannot dominate analysis. */
        private const val MAX_WINDOWS = 12
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

    /**
     * Embeds [lyricsText] into a 384-dim, L2-normalized embedding covering the whole song.
     *
     * The model reads at most [MAX_SEQ_LEN] tokens at a time, which is roughly a single verse, so
     * long lyrics are split into overlapping windows and their embeddings averaged. Embedding only
     * the first window -- which is what this used to do -- means a song is represented by its
     * opening lines, and anything it is actually about that arrives in a later verse, a bridge or
     * a chorus payoff simply is not in the vector.
     *
     * Windows overlap so a phrase spanning a boundary is whole in at least one of them.
     */
    fun embed(lyricsText: String): AiModelResult<FloatArray> {
        if (lyricsText.isBlank()) return AiModelResult.Unavailable("blank lyrics text")
        if (!ensureInitialized()) return AiModelResult.Unavailable("model unavailable")

        val windows = windowsOf(lyricsText)
        if (windows.isEmpty()) return AiModelResult.Unavailable("blank lyrics text")

        val pooled = FloatArray(EMBEDDING_DIM)
        var embedded = 0
        for (window in windows) {
            when (val result = embedWindow(window)) {
                is AiModelResult.Success -> {
                    for (i in 0 until EMBEDDING_DIM) pooled[i] += result.value[i]
                    embedded++
                }
                // One bad window should not lose the whole song; the rest still describe it.
                is AiModelResult.Unavailable -> Log.d(TAG, "Skipped a lyrics window: ${result.reason}")
                is AiModelResult.Error -> Log.w(TAG, "Skipped a lyrics window", result.throwable)
            }
        }
        if (embedded == 0) return AiModelResult.Unavailable("no lyrics window could be embedded")

        for (i in pooled.indices) pooled[i] /= embedded
        return AiModelResult.Success(l2Normalize(pooled))
    }

    /**
     * Splits [text] into overlapping word windows sized to fit [MAX_SEQ_LEN] wordpiece tokens.
     *
     * Words rather than tokens because the tokenizer exposes no streaming interface, and the
     * budget is deliberately conservative: wordpiece splits unusual words into several tokens, so
     * assuming fewer words per window keeps the tail of a window from being silently truncated.
     */
    private fun windowsOf(text: String): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        if (words.size <= WINDOW_WORDS) return listOf(text)

        val windows = mutableListOf<String>()
        var start = 0
        while (start < words.size && windows.size < MAX_WINDOWS) {
            val end = minOf(start + WINDOW_WORDS, words.size)
            windows.add(words.subList(start, end).joinToString(" "))
            if (end == words.size) break
            start += WINDOW_WORDS - WINDOW_OVERLAP_WORDS
        }
        return windows
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) sum += v.toDouble() * v
        val norm = kotlin.math.sqrt(sum).toFloat()
        if (norm <= 0f || !norm.isFinite()) return vector
        for (i in vector.indices) vector[i] /= norm
        return vector
    }

    /** Embeds one window that already fits the model's token budget. */
    private fun embedWindow(lyricsText: String): AiModelResult<FloatArray> {
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
