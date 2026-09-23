package com.example.tgmusicai.ai.whisper

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

/**
 * Computes the exact 80-bin log-mel spectrogram Whisper's own `WhisperFeatureExtractor` produces
 * from raw audio, reimplementing `whisper/audio.py`'s `log_mel_spectrogram` (reflect-padded STFT,
 * `n_fft=400`, `hop_length=160`, mel filterbank matmul, log10 + dynamic-range clamp + scale) so the
 * bundled ONNX encoder -- exported from the same reference model -- sees the input distribution it
 * was trained on. The 80x201 mel filter matrix in `assets/ai/whisper/mel_80_filters.bin` is the
 * actual filterbank from openai/whisper's own repo (extracted from its `mel_filters.npz`), not a
 * locally recomputed approximation, to avoid any filter-shape/normalization mismatch.
 */
internal object WhisperMelSpectrogram {
    const val N_FFT = 400
    const val HOP_LENGTH = 160
    const val N_MELS = 80
    const val N_SAMPLES = 480_000 // 30s at 16kHz -- Whisper always pads/truncates to one fixed-size window.
    const val N_FRAMES = 3000
    private const val FREQ_BINS = N_FFT / 2 + 1 // 201
    private const val MEL_FILTERS_ASSET = "ai/whisper/mel_80_filters.bin"

    private val hannWindow = DoubleArray(N_FFT) { n -> 0.5 - 0.5 * cos(2.0 * PI * n / N_FFT) }
    private val fft = WhisperFft(N_FFT)

    /** Loads the bundled [N_MELS] x [FREQ_BINS] mel filterbank once; cheap (~64KB), call per engine instance. */
    fun loadMelFilters(context: Context): Array<FloatArray> {
        val bytes = context.assets.open(MEL_FILTERS_ASSET).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return Array(N_MELS) { FloatArray(FREQ_BINS) { buffer.float } }
    }

    /**
     * Computes the log-mel spectrogram of [pcm] (mono, 16kHz, any length -- zero-padded/truncated
     * to exactly 30s here, matching Whisper's fixed-window behavior). Returns a flat row-major
     * `[N_MELS * N_FRAMES]` array (mel-major, i.e. `result[mel * N_FRAMES + frame]`), the layout
     * ONNX expects for the encoder's `input_features` tensor of shape `[1, 80, 3000]`.
     */
    fun compute(pcm: FloatArray, melFilters: Array<FloatArray>): FloatArray {
        val padded = DoubleArray(N_SAMPLES)
        val copyLen = min(pcm.size, N_SAMPLES)
        for (i in 0 until copyLen) padded[i] = pcm[i].toDouble()
        // Remaining entries stay 0.0 -- zero-padding for a track shorter than this 30s window.

        val reflectPad = N_FFT / 2 // 200
        val period = 2 * (N_SAMPLES - 1)
        fun reflectIndex(i: Int): Int {
            var ii = i % period
            if (ii < 0) ii += period
            return if (ii >= N_SAMPLES) period - ii else ii
        }

        val frameRe = DoubleArray(N_FFT)
        val outRe = DoubleArray(N_FFT)
        val outIm = DoubleArray(N_FFT)
        val power = DoubleArray(FREQ_BINS)
        val melSpec = Array(N_MELS) { DoubleArray(N_FRAMES) }

        for (t in 0 until N_FRAMES) {
            val frameStart = t * HOP_LENGTH - reflectPad // position in the (conceptually) reflect-padded signal
            for (n in 0 until N_FFT) {
                val srcIndex = reflectIndex(frameStart + n)
                frameRe[n] = padded[srcIndex] * hannWindow[n]
            }
            fft.transform(frameRe, outRe, outIm)
            for (f in 0 until FREQ_BINS) {
                val re = outRe[f]
                val im = outIm[f]
                power[f] = re * re + im * im
            }
            for (m in 0 until N_MELS) {
                var sum = 0.0
                val filterRow = melFilters[m]
                for (f in 0 until FREQ_BINS) sum += filterRow[f] * power[f]
                melSpec[m][t] = sum
            }
        }

        var globalMax = -Double.MAX_VALUE
        for (m in 0 until N_MELS) {
            for (t in 0 until N_FRAMES) {
                val logVal = log10(max(melSpec[m][t], 1e-10))
                melSpec[m][t] = logVal
                if (logVal > globalMax) globalMax = logVal
            }
        }
        val floor = globalMax - 8.0
        val result = FloatArray(N_MELS * N_FRAMES)
        for (m in 0 until N_MELS) {
            for (t in 0 until N_FRAMES) {
                val clamped = max(melSpec[m][t], floor)
                result[m * N_FRAMES + t] = ((clamped + 4.0) / 4.0).toFloat()
            }
        }
        return result
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b
}
