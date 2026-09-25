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
 * On-device speech-to-text lyric transcription via a bundled, quantized Whisper-tiny.en (ONNX,
 * ~40MB total for both the encoder and merged decoder -- see `assets/ai/whisper/`). Replaces the
 * app's previous design of calling OpenAI's hosted Whisper API: no API key, no per-call cost, no
 * network dependency, works fully offline once the song is downloaded. Model credit: OpenAI
 * (Whisper), ONNX export via `onnx-community/whisper-tiny.en` (MIT).
 *
 * Runs the same greedy-decode algorithm the reference model uses (forced `<|startoftranscript|>
 * <|notimestamps|>` prompt, `suppress_tokens`/`begin_suppress_tokens` exactly as configured in the
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

        private const val NUM_LAYERS = 4
        private const val NUM_HEADS = 6
        private const val HEAD_DIM = 64
        private const val ENCODER_SEQ_LEN = 1500

        // From the reference model's generation_config.json -- see this class's doc comment.
        // Hardcoded rather than parsed at runtime since these are fixed properties of the bundled
        // checkpoint, not something that varies per call.
        private const val SOT_TOKEN = 50257 // <|startoftranscript|> (also decoder_start_token_id)
        private const val NO_TIMESTAMPS_TOKEN = 50362
        private const val EOS_TOKEN = 50256 // <|endoftext|>
        private const val MAX_NEW_TOKENS = 224
        private val BEGIN_SUPPRESS_TOKENS = setOf(220, 50256)
        private val SUPPRESS_TOKENS = setOf(
            1, 2, 7, 8, 9, 10, 14, 25, 26, 27, 28, 29, 31, 58, 59, 60, 61, 62, 63, 90, 91, 92, 93,
            357, 366, 438, 532, 685, 705, 796, 930, 1058, 1220, 1267, 1279, 1303, 1343, 1377, 1391,
            1635, 1782, 1875, 2162, 2361, 2488, 3467, 4008, 4211, 4600, 4808, 5299, 5855, 6329, 7203,
            9609, 9959, 10563, 10786, 11420, 11709, 11907, 13163, 13697, 13700, 14808, 15306, 16410,
            16791, 17992, 19203, 19510, 20724, 22305, 22935, 27007, 30109, 30420, 33409, 34949,
            40283, 40493, 40549, 47282, 49146, 50257, 50357, 50358, 50359, 50360, 50361
        )

        // A chunk that decodes into the same token over and over (an instrumental stretch with no
        // vocals is a common trigger) is Whisper hallucinating/looping on silence, not real lyrics
        // -- stop rather than let it run to MAX_NEW_TOKENS every time.
        private const val MAX_REPEATED_TOKEN_RUN = 8

        /** Upper bound on ONNX Runtime worker threads, so transcription cannot starve playback. */
        private const val MAX_ORT_THREADS = 4
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
     * Transcribes the full song at [filePath] and returns it as an LRC-formatted string (one
     * `[mm:ss.xx] text` line per ~30s audio chunk -- the model's own native window size, so this
     * is the finest timestamp granularity a single-pass greedy decode can offer without also
     * decoding Whisper's internal timestamp tokens). [AiModelResult.Success] carries an empty
     * string if decoding worked but no chunk produced any text (e.g. a purely instrumental track).
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
                val chunkSamples = WhisperMelSpectrogram.N_SAMPLES
                var chunkStart = 0
                var previousText: String? = null
                while (chunkStart < pcm.size) {
                    val chunkEnd = minOf(chunkStart + chunkSamples, pcm.size)
                    val chunk = pcm.copyOfRange(chunkStart, chunkEnd)
                    val chunkStartMs = (chunkStart.toLong() * 1000L) / 16_000L
                    chunkStart += chunkSamples

                    // Near-silence is skipped without running the model at all. It is both the
                    // cheapest speed-up available -- intros, outros and gaps cost nothing instead
                    // of a full encode plus a 224-step decode -- and a correctness fix: Whisper is
                    // famous for inventing confident text out of silence, and those inventions are
                    // exactly the phantom lines that ended up repeated down a song.
                    if (WhisperDecodeGuards.isEffectivelySilent(chunk)) continue

                    val raw = transcribeChunk(chunk, filters, encoder, decoder, tok)
                    val text = WhisperDecodeGuards.collapseRepeatedPhrases(raw)
                    if (text.isBlank()) continue

                    // A chunk that says exactly what the previous one said is the model looping,
                    // not the song repeating a line 30 seconds later to the word.
                    if (previousText != null && text.equals(previousText, ignoreCase = true)) continue
                    previousText = text

                    lines.add("[${formatLrcTimestamp(chunkStartMs)}]$text")
                }
                AiModelResult.Success(lines.joinToString("\n"))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "On-device transcription failed for $filePath", e)
            AiModelResult.Error(e)
        }
    }

    private fun transcribeChunk(
        chunkPcm: FloatArray,
        filters: Array<FloatArray>,
        encoder: OrtSession,
        decoder: OrtSession,
        tok: WhisperTokenizer
    ): String {
        val env = OrtEnvironment.getEnvironment()
        val melFeatures = WhisperMelSpectrogram.compute(chunkPcm, filters)

        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(melFeatures),
            longArrayOf(1, WhisperMelSpectrogram.N_MELS.toLong(), WhisperMelSpectrogram.N_FRAMES.toLong())
        ).use { inputFeatures ->
            encoder.run(mapOf("input_features" to inputFeatures)).use { encoderOutputs ->
                val hiddenState = (encoderOutputs.get("last_hidden_state").orElse(null) as? OnnxTensor)
                    ?: return ""
                return runDecoderLoop(env, decoder, hiddenState, tok)
            }
        }
    }

    /** One layer's worth of self- or cross-attention KV cache, shape `[NUM_HEADS, seqLen, HEAD_DIM]` flattened row-major, or null for an empty (zero-length) cache. */
    private class LayerCache(var data: FloatArray?, var seqLen: Int)

    private fun runDecoderLoop(
        env: OrtEnvironment,
        decoder: OrtSession,
        encoderHiddenState: OnnxTensor,
        tok: WhisperTokenizer
    ): String {
        val decoderCacheKey = Array(NUM_LAYERS) { LayerCache(null, 0) }
        val decoderCacheValue = Array(NUM_LAYERS) { LayerCache(null, 0) }
        // Cross-attention KV is computed once (step 0, use_cache_branch=false) from
        // encoderHiddenState and never changes for the rest of this chunk's decode.
        val encoderCacheKey = Array(NUM_LAYERS) { LayerCache(null, 0) }
        val encoderCacheValue = Array(NUM_LAYERS) { LayerCache(null, 0) }

        val generated = mutableListOf<Int>()
        var nextInputIds = longArrayOf(SOT_TOKEN.toLong(), NO_TIMESTAMPS_TOKEN.toLong())
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
                    val logitsTensor = outputs.get("logits").orElse(null) as? OnnxTensor ?: return tok.decode(generated)
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
            if (WhisperDecodeGuards.endsInRepeatedCycle(generated)) break

            nextInputIds = longArrayOf(bestId.toLong())
            useCache = true
        }

        return tok.decode(generated)
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
