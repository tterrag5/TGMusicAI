package com.example.tgmusicai.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * Turns a song's audio into a 1280-dimension embedding that places it in a musical space, using
 * EffNet-Discogs (Music Technology Group, UPF) through ONNX Runtime.
 *
 * This is the acoustic signal behind recommendations, and it replaced YAMNet's class scores for
 * that job. YAMNet is a general audio-event classifier -- it knows "electric guitar" and "speech"
 * and "dog barking" -- and the variant bundled here exposes only its 521 event scores, no
 * embedding layer. EffNet-Discogs is an EfficientNet-B0 trained on over two million recordings
 * against the Discogs style taxonomy, with a penultimate dense layer put there specifically so it
 * can be used as an embedding extractor. Published comparisons find its embeddings more consistent
 * for music similarity than VGGish-AudioSet or MusiCNN. YAMNet stays for tagging, which is what it
 * is good at.
 *
 * Follows the same containment rules as every other engine here: it self-initializes behind a
 * try-catch, marks itself permanently unavailable on failure instead of retrying, and returns
 * [AiModelResult] rather than throwing. A failure here must never be able to reach playback.
 */
class MusicEmbeddingEngine(private val context: Context) {

    private var session: OrtSession? = null
    private var initFailed = false
    private val initLock = Any()

    val isAvailable: Boolean get() = session != null

    private fun ensureInitialized(): Boolean {
        if (isAvailable) return true
        if (initFailed) return false
        synchronized(initLock) {
            if (isAvailable) return true
            if (initFailed) return false
            return try {
                val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                session = env.createSession(modelBytes, OrtSession.SessionOptions())
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize the music embedding engine -- feature stays disabled", e)
                initFailed = true
                session = null
                false
            }
        }
    }

    /**
     * Embeds the audio at [filePath] into a single 1280-dimension vector.
     *
     * The model reads about two seconds at a time, so a track becomes many patches whose
     * embeddings are averaged into one. Averaging is what Essentia's own reference pipeline does
     * for track-level embeddings, and it is what makes the result a description of the song rather
     * than of whichever moment happened to be sampled.
     *
     * Patches are taken from across the whole track rather than the first slice of it. A song's
     * opening is frequently unrepresentative -- an intro, silence, a spoken sample -- and judging
     * similarity from it alone is how a recommender ends up pairing two songs because both start
     * quietly.
     */
    fun embedAudioFile(filePath: String): AiModelResult<FloatArray> {
        if (!ensureInitialized()) return AiModelResult.Unavailable("model unavailable")
        val ortSession = session ?: return AiModelResult.Unavailable("model unavailable")

        val pcm = PcmDecoder.decodeToMonoPcm16k(filePath, maxDurationSec = ANALYSIS_WINDOW_SEC)
            ?: return AiModelResult.Unavailable("could not decode audio")
        if (pcm.size < MusiCnnMelSpectrogram.PATCH_SAMPLES) {
            return AiModelResult.Unavailable("audio too short to embed")
        }

        return try {
            val patches = MusiCnnMelSpectrogram.patchesFrom(pcm)
            if (patches.isEmpty()) return AiModelResult.Unavailable("no usable audio patches")

            val selected = spreadAcross(patches, MAX_PATCHES)
            val embedding = synchronized(initLock) { runBatch(ortSession, selected) }
                ?: return AiModelResult.Unavailable("model produced no embedding")

            AiModelResult.Success(embedding)
        } catch (e: Throwable) {
            Log.e(TAG, "Music embedding inference failed for this call ($filePath)", e)
            AiModelResult.Error(e)
        }
    }

    /** Runs every patch through the model in one pass and mean-pools the embeddings. */
    private fun runBatch(ortSession: OrtSession, patches: List<Array<FloatArray>>): FloatArray? {
        val env = OrtEnvironment.getEnvironment()
        val batch = patches.size
        val frames = MusiCnnMelSpectrogram.PATCH_FRAMES
        val bands = MusiCnnMelSpectrogram.MEL_BANDS

        val flat = FloatBuffer.allocate(batch * frames * bands)
        for (patch in patches) {
            for (row in patch) flat.put(row)
        }
        flat.rewind()

        val shape = longArrayOf(batch.toLong(), frames.toLong(), bands.toLong())
        OnnxTensor.createTensor(env, flat, shape).use { input ->
            val inputName = ortSession.inputNames.first()
            ortSession.run(mapOf(inputName to input)).use { results ->
                // The model returns style predictions and embeddings; the embedding output is the
                // 1280-wide one. Selecting it by width rather than by name or position keeps this
                // working if the export's output order or naming changes.
                val embeddings = results.asSequence()
                    .mapNotNull { it.value.value as? Array<*> }
                    .firstOrNull { rows ->
                        (rows.firstOrNull() as? FloatArray)?.size == EMBEDDING_DIM
                    } ?: return null

                val pooled = FloatArray(EMBEDDING_DIM)
                var counted = 0
                for (row in embeddings) {
                    val values = row as? FloatArray ?: continue
                    for (i in 0 until EMBEDDING_DIM) pooled[i] += values[i]
                    counted++
                }
                if (counted == 0) return null
                for (i in pooled.indices) pooled[i] /= counted
                return pooled
            }
        }
    }

    /**
     * Picks at most [limit] patches spread evenly across [patches], so the sample describes the
     * whole track instead of its first few seconds.
     */
    private fun <T> spreadAcross(patches: List<T>, limit: Int): List<T> {
        if (patches.size <= limit) return patches
        val step = patches.size.toDouble() / limit
        return (0 until limit).map { patches[(it * step).toInt().coerceAtMost(patches.lastIndex)] }
    }

    fun release() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close the music embedding session", e)
        } finally {
            session = null
        }
    }

    companion object {
        private const val TAG = "MusicEmbeddingEngine"
        private const val MODEL_ASSET = "ai/effnet_discogs.onnx"

        /** Width of the model's embedding output. */
        const val EMBEDDING_DIM = 1280

        /**
         * How much of a track is decoded for analysis. Ninety seconds reaches well past a typical
         * intro and into the body of most songs without decoding the whole file.
         */
        private const val ANALYSIS_WINDOW_SEC = 90

        /**
         * Patches per track. Sixteen spread over the analysis window is enough to average out a
         * quiet intro or a breakdown, and keeps one inference pass small enough to stay quick on a
         * phone.
         */
        private const val MAX_PATCHES = 16
    }
}
