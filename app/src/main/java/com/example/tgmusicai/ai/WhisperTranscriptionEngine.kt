package com.example.tgmusicai.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.tgmusicai.ai.whisper.WhisperDecodeGuards
import com.example.tgmusicai.ai.whisper.WhisperMelSpectrogram
import com.example.tgmusicai.ai.whisper.WhisperTokenizer
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device speech-to-text lyric transcription via a bundled, quantized Whisper-base (ONNX, ~77MB
 * total for both the encoder and merged decoder -- see `assets/ai/whisper/`). Replaces the app's
 * previous design of calling OpenAI's hosted Whisper API: no API key, no per-call cost, no network
 * dependency, works fully offline once the song is downloaded. Model credit: OpenAI (Whisper), ONNX
 * export via `onnx-community/whisper-base` (MIT).
 *
 * **base, and multilingual, rather than tiny.en.** tiny is the smallest Whisper there is, and sung
 * vocals over a full mix are already outside what a speech model handles well; at that size the
 * output was not usable. base roughly doubles the parameter count for about twice the assets, and
 * being multilingual it can also transcribe songs that are not in English, which the previous
 * English-only checkpoint answered with confident nonsense. Transcribing singing remains the
 * hardest thing this model is asked to do and it will still get lines wrong.
 *
 * Runs the same greedy-decode algorithm the reference model uses (forced `<|startoftranscript|>
 * <|lang|> <|transcribe|>` prompt, `suppress_tokens`/`begin_suppress_tokens` exactly as configured in the
 * reference `generation_config.json`, self-attention KV-cache reuse across steps, frozen
 * cross-attention KV computed once per 30s audio chunk) against the merged decoder graph exported
 * by Optimum, which folds the "first step" (no cache) and "later steps" (cached) branches into one
 * ONNX graph selected via its `use_cache_branch` input.
 *
 * Lazily and defensively initialized like the other `ai/` engines: any failure to load models/
 * tokenizer permanently disables the engine for this process rather than retrying per call.
 */
class WhisperTranscriptionEngine(private val context: Context) {

    companion object {
        private const val TAG = "WhisperTranscription"
        private const val ENCODER_ASSET = "ai/whisper/encoder_model.onnx"
        private const val DECODER_ASSET = "ai/whisper/decoder_model_merged.onnx"
        private const val VOCAB_ASSET = "ai/whisper/vocab.json"

        private const val NUM_LAYERS = 6
        private const val NUM_HEADS = 8
        private const val HEAD_DIM = 64
        private const val ENCODER_SEQ_LEN = 1500

        // From the reference model's generation_config.json -- see this class's doc comment.
        // Hardcoded rather than parsed at runtime since these are fixed properties of the bundled
        // checkpoint, not something that varies per call.
        // Every one of these moved with the model. whisper-base is multilingual, and its
        // vocabulary inserts 99 language tokens and the task tokens that an English-only model has
        // no use for. Taken from the checkpoint's own generation_config.json rather than from
        // memory: an off-by-one here does not fail loudly, it decodes from the wrong starting state
        // and returns fluent nonsense.
        private const val SOT_TOKEN = 50258 // <|startoftranscript|> (also decoder_start_token_id)
        private const val EOS_TOKEN = 50257 // <|endoftext|>
        private const val TRANSCRIBE_TOKEN = 50359
        private const val NO_TIMESTAMPS_TOKEN = 50363

        /** The 99 `<|xx|>` language tokens, contiguous, in the order the model was trained on. */
        private const val FIRST_LANGUAGE_TOKEN = 50259
        private const val LAST_LANGUAGE_TOKEN = 50357

        /** `<|0.00|>`. Timestamps run from here in 20ms steps to `<|30.00|>`. */
        private const val FIRST_TIMESTAMP_TOKEN = 50364
        private const val TIMESTAMP_STEP_SECONDS = 0.02

        private const val MAX_NEW_TOKENS = 224
        private val BEGIN_SUPPRESS_TOKENS = setOf(220, 50257)
        private val SUPPRESS_TOKENS = setOf(
            1, 2, 7, 8, 9, 10, 14, 25, 26, 27, 28, 29, 31, 58, 59, 60, 61, 62, 63, 90, 91, 92, 93,
            359, 503, 522, 542, 873, 893, 902, 918, 922, 931, 1350, 1853, 1982, 2460, 2627, 3246,
            3253, 3268, 3536, 3846, 3961, 4183, 4667, 6585, 6647, 7273, 9061, 9383, 10428, 10929,
            11938, 12033, 12331, 12562, 13793, 14157, 14635, 15265, 15618, 16553, 16604, 18362,
            18956, 20075, 21675, 22520, 26130, 26161, 26435, 28279, 29464, 31650, 32302, 32470,
            36865, 42863, 47425, 49870, 50254, 50258, 50358, 50359, 50360, 50361, 50362
        )

        // A chunk that decodes into the same token over and over (an instrumental stretch with no
        // vocals is a common trigger) is Whisper hallucinating/looping on silence, not real lyrics
        // -- stop rather than let it run to MAX_NEW_TOKENS every time.
        private const val MAX_REPEATED_TOKEN_RUN = 8

        /** Upper bound on ONNX Runtime worker threads, so transcription cannot starve playback. */
        private const val MAX_ORT_THREADS = 4

        /**
         * Least the window may advance after a chunk, in samples (5s).
         *
         * The window normally advances to the end of the last line the model actually finished, so
         * the next pass starts at a line boundary instead of halfway through a word. A pathological
         * chunk can report a first line ending almost immediately, though, and honouring that would
         * crawl through the song a fraction of a second at a time -- or, at zero, never finish.
         */
        private const val MIN_ADVANCE_SAMPLES = 5 * 16_000
    }

    @Volatile private var initFailed = false
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: WhisperTokenizer? = null
    private var melFilters: Array<FloatArray>? = null
    private val initLock = Any()

    val isAvailable: Boolean get() = encoderSession != null && decoderSession != null

    private fun sessionOptions(threads: Int) = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }

    private fun ensureInitialized(): Boolean {
        if (isAvailable) return true
        if (initFailed) return false
        synchronized(initLock) {
            if (isAvailable) return true
            if (initFailed) return false
            try {
                val env = OrtEnvironment.getEnvironment()
                val encoderBytes = context.assets.open(ENCODER_ASSET).use { it.readBytes() }
                val decoderBytes = context.assets.open(DECODER_ASSET).use { it.readBytes() }
                // Defaults leave ONNX Runtime single-threaded here, which is most of why a song
                // took minutes: the decoder is run once per generated token, hundreds of times per
                // chunk. Capped rather than handed every core -- transcription is a background
                // favour to the user and must not starve playback, which is the one thing on this
                // device that cannot be allowed to stutter.
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_ORT_THREADS)
                encoderSession = env.createSession(encoderBytes, sessionOptions(threads))
                decoderSession = env.createSession(decoderBytes, sessionOptions(threads))
                tokenizer = WhisperTokenizer.fromAsset(context, VOCAB_ASSET)
                melFilters = WhisperMelSpectrogram.loadMelFilters(context)
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize on-device transcription engine -- feature will stay disabled", e)
                initFailed = true
                encoderSession?.close()
                decoderSession?.close()
                encoderSession = null
                decoderSession = null
                tokenizer = null
                melFilters = null
                false
            }
        }
        return isAvailable
    }

    /**
     * Transcribes the song at [filePath] and returns it as LRC, one `[mm:ss.xx] line` per sung line.
     *
     * The audio is worked through in Whisper's native 30-second windows -- the model cannot accept
     * more than that at once -- but the windows are not a fixed grid. The model is asked for its own
     * timestamps, which gives two things a fixed grid cannot: a real start time for every line
     * rather than one lump of text per half-minute, and a place to resume. Each window advances to
     * the end of the last line the model actually finished, so the next pass starts at a line
     * boundary instead of halfway through a word, which is where a fixed 30-second step landed most
     * of the time.
     *
     * The spoken language is detected once, from the first window with sound in it, and then held
     * for the rest of the song. Detecting per window lets one instrumental passage flip the model
     * into another language mid-song, and a song does not change language halfway through.
     *
     * [AiModelResult.Success] carries an empty string when decoding worked but produced no text --
     * an instrumental, or a track whose vocals the model could not make out.
     */
    fun transcribeFile(filePath: String): AiModelResult<String> {
        if (!ensureInitialized()) return AiModelResult.Unavailable("model unavailable")
        val encoder = encoderSession ?: return AiModelResult.Unavailable("model unavailable")
        val decoder = decoderSession ?: return AiModelResult.Unavailable("model unavailable")
        val tok = tokenizer ?: return AiModelResult.Unavailable("tokenizer unavailable")
        val filters = melFilters ?: return AiModelResult.Unavailable("mel filters unavailable")

        val pcm = PcmDecoder.decodeFullMonoPcm16k(context, filePath)
            ?: return AiModelResult.Unavailable("could not decode audio")
        if (pcm.isEmpty()) return AiModelResult.Unavailable("empty audio")

        return try {
            synchronized(initLock) {
                val lines = mutableListOf<String>()
                val windowSamples = WhisperMelSpectrogram.N_SAMPLES
                var cursor = 0
                var language: Int? = null
                var previousText: String? = null

                while (cursor < pcm.size) {
                    val window = pcm.copyOfRange(cursor, minOf(cursor + windowSamples, pcm.size))

                    // Silence is skipped without touching the model: the cheapest speed-up there
                    // is, and a correctness fix too, since Whisper is famous for inventing
                    // confident text out of nothing.
                    if (WhisperDecodeGuards.isEffectivelySilent(window)) {
                        cursor += windowSamples
                        continue
                    }

                    val segments = withEncodedWindow(window, filters, encoder, OrtEnvironment.getEnvironment()) { env, hidden ->
                        if (language == null) language = detectLanguage(env, decoder, hidden)
                        val tokens = runDecoderLoop(env, decoder, hidden, promptFor(language))
                        parseSegments(tokens, tok)
                    }.orEmpty()

                    for (segment in segments) {
                        val text = WhisperDecodeGuards.collapseRepeatedPhrases(segment.text)
                        if (text.isBlank()) continue
                        // A line identical to the one before it is the model looping, not the song
                        // repeating itself to the word a few seconds later.
                        if (text.equals(previousText, ignoreCase = true)) continue
                        previousText = text
                        val atMs = (cursor.toLong() * 1000L) / 16_000L + (segment.startSeconds * 1000).toLong()
                        lines.add("[${formatLrcTimestamp(atMs)}]$text")
                    }

                    cursor += advanceFor(segments, windowSamples)
                }
                AiModelResult.Success(lines.joinToString("\n"))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "On-device transcription failed for $filePath", e)
            AiModelResult.Error(e)
        }
    }

    /** How far to move the window: to the end of the last finished line, or a whole window. */
    private fun advanceFor(segments: List<Segment>, windowSamples: Int): Int {
        val lastEnd = segments.lastOrNull { it.endSeconds != null }?.endSeconds ?: return windowSamples
        val samples = (lastEnd * 16_000).toInt()
        return samples.coerceIn(MIN_ADVANCE_SAMPLES, windowSamples)
    }

    /** The forced prompt that starts a decode: transcribe, in this language, with timestamps. */
    private fun promptFor(language: Int?): LongArray = longArrayOf(
        SOT_TOKEN.toLong(),
        (language ?: (FIRST_LANGUAGE_TOKEN)).toLong(),
        TRANSCRIBE_TOKEN.toLong()
    )

    /** One line the model marked out for itself, with the window-relative times it gave it. */
    private class Segment(val startSeconds: Double, val endSeconds: Double?, val text: String)

    /**
     * Splits a decoded token run into lines on the model's own timestamp tokens.
     *
     * Whisper emits `<|start|> text <|end|>` for each utterance it is confident about. A trailing
     * run with no closing timestamp is still returned -- it is real text, it just cannot be used to
     * decide where the next window begins, which is why [Segment.endSeconds] is nullable.
     */
    private fun parseSegments(tokens: List<Int>, tok: WhisperTokenizer): List<Segment> {
        val segments = mutableListOf<Segment>()
        var start: Double? = null
        val buffer = mutableListOf<Int>()
        for (token in tokens) {
            if (token >= FIRST_TIMESTAMP_TOKEN) {
                val seconds = (token - FIRST_TIMESTAMP_TOKEN) * TIMESTAMP_STEP_SECONDS
                if (start == null) {
                    start = seconds
                } else {
                    if (buffer.isNotEmpty()) {
                        segments.add(Segment(start, seconds, tok.decode(buffer).trim()))
                        buffer.clear()
                    }
                    start = null
                }
            } else {
                buffer.add(token)
            }
        }
        if (buffer.isNotEmpty()) {
            segments.add(Segment(start ?: 0.0, null, tok.decode(buffer).trim()))
        }
        return segments.filter { it.text.isNotBlank() }
    }

    /**
     * Encodes one window and hands the encoder's hidden state to [block], closing both afterwards.
     * Exists so the encoder output's lifetime is obvious at the call site -- it is reused across the
     * language probe and the decode, and leaking it once per window would leak it per half-minute
     * of every song transcribed.
     */
    private fun <T> withEncodedWindow(
        window: FloatArray,
        filters: Array<FloatArray>,
        encoder: OrtSession,
        env: OrtEnvironment,
        block: (OrtEnvironment, OnnxTensor) -> T
    ): T? {
        val melFeatures = WhisperMelSpectrogram.compute(window, filters)
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(melFeatures),
            longArrayOf(1, WhisperMelSpectrogram.N_MELS.toLong(), WhisperMelSpectrogram.N_FRAMES.toLong())
        ).use { inputFeatures ->
            encoder.run(mapOf("input_features" to inputFeatures)).use { outputs ->
                // No hidden state means the encoder gave us nothing to decode. Returning null
                // rather than inventing an empty tensor: handing the decoder a zero-length encoder
                // output would produce output, and that output would be fiction.
                val hidden = (outputs.get("last_hidden_state").orElse(null) as? OnnxTensor)
                    ?: return null
                return block(env, hidden)
            }
        }
    }

    /**
     * Asks the model which language it is hearing: one decode step from the bare
     * `<|startoftranscript|>` prompt, whose next-token distribution over the language tokens is
     * exactly the language classifier Whisper was trained to expose.
     */
    private fun detectLanguage(env: OrtEnvironment, decoder: OrtSession, hidden: OnnxTensor): Int {
        val logits = singleStepLogits(env, decoder, hidden, longArrayOf(SOT_TOKEN.toLong()))
            ?: return FIRST_LANGUAGE_TOKEN
        var best = FIRST_LANGUAGE_TOKEN
        var bestValue = Float.NEGATIVE_INFINITY
        for (id in FIRST_LANGUAGE_TOKEN..LAST_LANGUAGE_TOKEN) {
            val value = logits(id)
            if (value > bestValue) {
                bestValue = value
                best = id
            }
        }
        return best
    }

    /** Runs the decoder once over [inputIds] with empty caches, returning a logit lookup or null. */
    private fun singleStepLogits(
        env: OrtEnvironment,
        decoder: OrtSession,
        hidden: OnnxTensor,
        inputIds: LongArray
    ): ((Int) -> Float)? {
        val tensorsToClose = mutableListOf<OnnxTensor>()
        try {
            val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong()))
            tensorsToClose.add(idsTensor)
            val useCacheTensor = OnnxTensor.createTensor(env, booleanArrayOf(false))
            tensorsToClose.add(useCacheTensor)
            val inputs = HashMap<String, OnnxTensor>()
            inputs["input_ids"] = idsTensor
            inputs["encoder_hidden_states"] = hidden
            inputs["use_cache_branch"] = useCacheTensor
            val empty = LayerCache(null, 0)
            for (layer in 0 until NUM_LAYERS) {
                val dk = makeKvTensor(env, empty); tensorsToClose.add(dk)
                val dv = makeKvTensor(env, empty); tensorsToClose.add(dv)
                val ek = makeKvTensor(env, empty); tensorsToClose.add(ek)
                val ev = makeKvTensor(env, empty); tensorsToClose.add(ev)
                inputs["past_key_values.$layer.decoder.key"] = dk
                inputs["past_key_values.$layer.decoder.value"] = dv
                inputs["past_key_values.$layer.encoder.key"] = ek
                inputs["past_key_values.$layer.encoder.value"] = ev
            }
            decoder.run(inputs).use { outputs ->
                val logitsTensor = outputs.get("logits").orElse(null) as? OnnxTensor ?: return null
                val shape = logitsTensor.info.shape
                val vocab = shape[2].toInt()
                val offset = (shape[1].toInt() - 1) * vocab
                val values = FloatArray(vocab)
                val buf = logitsTensor.floatBuffer
                for (v in 0 until vocab) values[v] = buf.get(offset + v)
                return { id -> if (id in values.indices) values[id] else Float.NEGATIVE_INFINITY }
            }
        } finally {
            for (t in tensorsToClose) t.close()
        }
    }

    /** One layer's worth of self- or cross-attention KV cache, shape `[NUM_HEADS, seqLen, HEAD_DIM]` flattened row-major, or null for an empty (zero-length) cache. */
    private class LayerCache(var data: FloatArray?, var seqLen: Int)

    private fun runDecoderLoop(
        env: OrtEnvironment,
        decoder: OrtSession,
        encoderHiddenState: OnnxTensor,
        prompt: LongArray
    ): List<Int> {
        val decoderCacheKey = Array(NUM_LAYERS) { LayerCache(null, 0) }
        val decoderCacheValue = Array(NUM_LAYERS) { LayerCache(null, 0) }
        // Cross-attention KV is computed once (step 0, use_cache_branch=false) from
        // encoderHiddenState and never changes for the rest of this chunk's decode.
        val encoderCacheKey = Array(NUM_LAYERS) { LayerCache(null, 0) }
        val encoderCacheValue = Array(NUM_LAYERS) { LayerCache(null, 0) }

        val generated = mutableListOf<Int>()
        var nextInputIds = prompt
        var useCache = false
        var repeatRun = 0
        var lastToken = -1

        for (step in 0 until MAX_NEW_TOKENS) {
            val tensorsToClose = mutableListOf<OnnxTensor>()
            var bestId = -1
            try {
                val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(nextInputIds), longArrayOf(1, nextInputIds.size.toLong()))
                tensorsToClose.add(inputIdsTensor)
                val useCacheTensor = OnnxTensor.createTensor(env, booleanArrayOf(useCache))
                tensorsToClose.add(useCacheTensor)

                val inputs = HashMap<String, OnnxTensor>()
                inputs["input_ids"] = inputIdsTensor
                inputs["encoder_hidden_states"] = encoderHiddenState
                inputs["use_cache_branch"] = useCacheTensor
                for (layer in 0 until NUM_LAYERS) {
                    val dk = makeKvTensor(env, decoderCacheKey[layer]); tensorsToClose.add(dk)
                    val dv = makeKvTensor(env, decoderCacheValue[layer]); tensorsToClose.add(dv)
                    val ek = makeKvTensor(env, encoderCacheKey[layer]); tensorsToClose.add(ek)
                    val ev = makeKvTensor(env, encoderCacheValue[layer]); tensorsToClose.add(ev)
                    inputs["past_key_values.$layer.decoder.key"] = dk
                    inputs["past_key_values.$layer.decoder.value"] = dv
                    inputs["past_key_values.$layer.encoder.key"] = ek
                    inputs["past_key_values.$layer.encoder.value"] = ev
                }

                decoder.run(inputs).use { outputs ->
                    val logitsTensor = outputs.get("logits").orElse(null) as? OnnxTensor ?: return generated
                    val logitsShape = logitsTensor.info.shape // [1, seqLen, vocab]
                    val seqLen = logitsShape[1].toInt()
                    val vocab = logitsShape[2].toInt()
                    val buf = logitsTensor.floatBuffer
                    val lastPosOffset = (seqLen - 1) * vocab

                    var candidateId = -1
                    var candidateVal = Float.NEGATIVE_INFINITY
                    for (v in 0 until vocab) {
                        if (v in SUPPRESS_TOKENS) continue
                        if (step == 0 && v in BEGIN_SUPPRESS_TOKENS) continue
                        val value = buf.get(lastPosOffset + v)
                        if (value > candidateVal) {
                            candidateVal = value
                            candidateId = v
                        }
                    }
                    bestId = candidateId

                    for (layer in 0 until NUM_LAYERS) {
                        updateCache(decoderCacheKey[layer], outputs.get("present.$layer.decoder.key").orElse(null) as? OnnxTensor)
                        updateCache(decoderCacheValue[layer], outputs.get("present.$layer.decoder.value").orElse(null) as? OnnxTensor)
                        if (!useCache) {
                            updateCache(encoderCacheKey[layer], outputs.get("present.$layer.encoder.key").orElse(null) as? OnnxTensor)
                            updateCache(encoderCacheValue[layer], outputs.get("present.$layer.encoder.value").orElse(null) as? OnnxTensor)
                        }
                    }
                }
            } finally {
                for (t in tensorsToClose) t.close()
            }

            if (bestId < 0 || bestId == EOS_TOKEN) break
            generated.add(bestId)
            repeatRun = if (bestId == lastToken) repeatRun + 1 else 0
            lastToken = bestId
            if (repeatRun >= MAX_REPEATED_TOKEN_RUN) break
            // The single-token guard above only catches "aaaa". Greedy decoding on music gets
            // stuck in *cycles* -- a phrase of several tokens repeated until the token budget runs
            // out -- which is what filled the lyrics pane with the same line hundreds of times, and
            // what made every chunk take the full 224 steps.
            if (WhisperDecodeGuards.endsInRepeatedCycle(generated.filter { it < FIRST_TIMESTAMP_TOKEN })) break

            nextInputIds = longArrayOf(bestId.toLong())
            useCache = true
        }

        return generated
    }

    /** Builds an OnnxTensor `[1, NUM_HEADS, seqLen, HEAD_DIM]` from [cache], or a valid zero-length-seq placeholder if empty -- every declared graph input must be fed every run regardless of which internal branch (`use_cache_branch`) actually consumes it. */
    private fun makeKvTensor(env: OrtEnvironment, cache: LayerCache): OnnxTensor {
        val data = cache.data ?: FloatArray(0)
        return OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data), longArrayOf(1, NUM_HEADS.toLong(), cache.seqLen.toLong(), HEAD_DIM.toLong())
        )
    }

    /** Replaces [cache]'s contents with [tensor]'s data (the model's `present.*` output for this step), or leaves it untouched if [tensor] is absent. */
    private fun updateCache(cache: LayerCache, tensor: OnnxTensor?) {
        if (tensor == null) return
        val shape = tensor.info.shape // [1, NUM_HEADS, seqLen, HEAD_DIM]
        val seqLen = shape[2].toInt()
        val floats = FloatArray((shape[1] * shape[2] * shape[3]).toInt())
        tensor.floatBuffer.get(floats)
        cache.data = floats
        cache.seqLen = seqLen
    }

    private fun formatLrcTimestamp(ms: Long): String {
        val totalCentis = ms / 10
        val minutes = totalCentis / 6000
        val seconds = (totalCentis / 100) % 60
        val centis = totalCentis % 100
        return "%02d:%02d.%02d".format(minutes, seconds, centis)
    }

    fun release() {
        try {
            encoderSession?.close()
            decoderSession?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ONNX sessions", e)
        }
        encoderSession = null
        decoderSession = null
        tokenizer = null
        melFilters = null
    }
}
