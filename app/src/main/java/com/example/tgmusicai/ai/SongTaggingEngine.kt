package com.example.tgmusicai.ai

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps Google's YAMNet TFLite model (Apache 2.0, ~4MB, 521 AudioSet classes) to derive a handful
 * of music-relevant tags (genre/instrument/mood) directly from a song's audio, for grouping and
 * "Start Radio" similarity. Lazily and defensively initialized: a failure to load the model
 * permanently (for this process) marks the engine unavailable rather than retrying or throwing --
 * every public function returns [AiModelResult] instead of propagating exceptions, and a bad
 * per-call input/decode failure never disables the engine for the next call.
 */
/**
 * A song's tags alongside the raw mean-pooled YAMNet class scores they were derived from. The
 * scores are what [com.example.tgmusicai.ai.AudioProfileCodec] stores as an acoustic profile.
 */
data class AudioProfile(val tags: List<String>, val scores: FloatArray) {
    // FloatArray gives identity equals/hashCode, which would silently break any structural
    // comparison of this class, so both are defined over the array's contents.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioProfile) return false
        return tags == other.tags && scores.contentEquals(other.scores)
    }

    override fun hashCode(): Int = 31 * tags.hashCode() + scores.contentHashCode()
}

class SongTaggingEngine(private val context: Context) {

    companion object {
        private const val TAG = "SongTaggingEngine"
        private const val MODEL_ASSET = "ai/yamnet.tflite"
        private const val CLASS_MAP_ASSET = "ai/yamnet_class_map.csv"
        /** AudioSet class indices 132-276 cover Music/instruments/genres/mood -- see yamnet_class_map.csv. */
        private val MUSIC_LABEL_RANGE = 132..276
        // Real on-device scores for this pooled classification model run lower than a typical
        // single-label softmax would suggest: a clearly correct "Music" tag often lands around
        // 0.3-0.4, with real secondary genre/instrument tags (e.g. "Guitar", "Bass guitar") down
        // around 0.08-0.15. 0.15 was cutting out real, correct secondary tags; verified against
        // real audio on-device before lowering.
        private const val SCORE_THRESHOLD = 0.08f
        private const val MAX_TAGS = 8
    }

    @Volatile private var initFailed = false
    private var interpreter: Interpreter? = null
    private var labels: List<String>? = null
    private val initLock = Any()

    val isAvailable: Boolean get() = interpreter != null

    private fun ensureInitialized(): Boolean {
        if (isAvailable) return true
        if (initFailed) return false
        synchronized(initLock) {
            if (isAvailable) return true
            if (initFailed) return false
            try {
                val modelBuffer = loadAssetAsDirectByteBuffer(MODEL_ASSET)
                interpreter = Interpreter(modelBuffer)
                labels = loadLabels()
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize song tagging engine -- feature will stay disabled", e)
                initFailed = true
                interpreter?.close()
                interpreter = null
                labels = null
                false
            }
        }
        return isAvailable
    }

    private fun loadAssetAsDirectByteBuffer(assetPath: String): ByteBuffer {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).apply {
            order(ByteOrder.nativeOrder())
            put(bytes)
            rewind()
        }
    }

    private fun loadLabels(): List<String> {
        val names = mutableListOf<String>()
        BufferedReader(InputStreamReader(context.assets.open(CLASS_MAP_ASSET), Charsets.UTF_8)).use { reader ->
            reader.readLine() // header row
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                names.add(parseDisplayName(line!!))
            }
        }
        return names
    }

    /** Parses the `display_name` column of a `index,mid,display_name` CSV row, handling quoted commas. */
    private fun parseDisplayName(csvLine: String): String {
        val firstComma = csvLine.indexOf(',')
        val secondComma = csvLine.indexOf(',', firstComma + 1)
        var rest = csvLine.substring(secondComma + 1)
        if (rest.startsWith("\"") && rest.endsWith("\"")) {
            rest = rest.substring(1, rest.length - 1)
        }
        return rest
    }

    /**
     * Tags the audio at [filePath] with up to [MAX_TAGS] music-relevant labels (genre/instrument/
     * mood), derived from decoding a short window of the file and mean-pooling YAMNet's per-frame
     * class scores. Returns [AiModelResult.Unavailable] if the model or the file can't be used.
     */
    fun tagAudioFile(filePath: String): AiModelResult<List<String>> =
        when (val result = profileAudioFile(filePath)) {
            is AiModelResult.Success -> AiModelResult.Success(result.value.tags)
            is AiModelResult.Unavailable -> AiModelResult.Unavailable(result.reason)
            is AiModelResult.Error -> AiModelResult.Error(result.throwable)
        }

    /**
     * Tags the audio at [filePath] and also returns the mean-pooled class-score vector the tags
     * were picked from.
     *
     * The vector is the tagging work's real output -- the eight label strings are a lossy summary
     * of it -- and it doubles as an acoustic fingerprint for
     * [com.example.tgmusicai.data.repository.RecommendationEngine]. This model is the
     * `yamnet/classification` variant, whose only output tensor is the 521 class scores (verified
     * on device: `outputCount=1`), so these scores, not a hidden embedding layer, are the richest
     * acoustic representation available without shipping another model.
     */
    fun profileAudioFile(filePath: String): AiModelResult<AudioProfile> {
        if (!ensureInitialized()) return AiModelResult.Unavailable("model unavailable")
        val interp = interpreter ?: return AiModelResult.Unavailable("model unavailable")
        val labelNames = labels ?: return AiModelResult.Unavailable("labels unavailable")

        val pcm = PcmDecoder.decodeToMonoPcm16k(filePath)
            ?: return AiModelResult.Unavailable("could not decode audio")
        if (pcm.isEmpty()) return AiModelResult.Unavailable("empty audio")

        return try {
            synchronized(initLock) {
                interp.resizeInput(0, intArrayOf(pcm.size))
                interp.allocateTensors()
                val outputShape = interp.getOutputTensor(0).shape() // [numFrames, numClasses], concrete post-allocate
                val numFrames = outputShape[0]
                val numClasses = outputShape[1]
                val output = Array(numFrames) { FloatArray(numClasses) }
                interp.run(pcm, output)

                val meanScores = FloatArray(numClasses)
                for (frame in output) {
                    for (classIndex in frame.indices) meanScores[classIndex] += frame[classIndex]
                }
                for (classIndex in meanScores.indices) meanScores[classIndex] /= numFrames

                val tags = MUSIC_LABEL_RANGE
                    .filter { it < meanScores.size && meanScores[it] >= SCORE_THRESHOLD }
                    .sortedByDescending { meanScores[it] }
                    .take(MAX_TAGS)
                    .mapNotNull { labelNames.getOrNull(it) }

                AiModelResult.Success(AudioProfile(tags = tags, scores = meanScores))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Audio tagging inference failed for this call ($filePath)", e)
            AiModelResult.Error(e)
        }
    }

    fun release() {
        try {
            interpreter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TFLite interpreter", e)
        }
        interpreter = null
        labels = null
    }
}
