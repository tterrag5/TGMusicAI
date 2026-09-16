package com.example.tgmusicai.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Decodes a short window of an audio file into 16kHz mono float PCM samples in `[-1, 1]`, the
 * input format [SongTaggingEngine] (YAMNet) expects. Used only for on-device tagging -- never for
 * actual playback -- so a decode failure here has zero effect on the app's real audio pipeline.
 * Uses the synchronous `MediaCodec` API, which is fine for this one-shot batch decode.
 */
object PcmDecoder {
    private const val TAG = "PcmDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TIMEOUT_US = 10_000L

    /** Returns up to [maxDurationSec] seconds of mono 16kHz float PCM decoded from [filePath], or null on any failure. */
    fun decodeToMonoPcm16k(filePath: String, maxDurationSec: Int = 15): FloatArray? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(filePath) }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null.also { Log.w(TAG, "No audio track found in $filePath") }

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val nativeSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(trackIndex)

            // Sample from ~25% into the track rather than position 0: many songs open with a
            // quiet/ambient intro that isn't representative of the track as a whole, which was
            // making tagging consistently under-confident. Falls back to position 0 for anything
            // too short for that to make sense.
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                val windowUs = maxDurationSec * 1_000_000L
                if (durationUs > windowUs * 2) {
                    extractor.seekTo(durationUs / 4, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
            }

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val maxSamplesNative = nativeSampleRate.toLong() * maxDurationSec
            val pcmChunks = mutableListOf<ShortArray>()
            var totalSamplesDecoded = 0L
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos && totalSamplesDecoded < maxSamplesNative) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo()
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuffer = outputBuffer.asShortBuffer()
                            val samples = ShortArray(shortBuffer.remaining())
                            shortBuffer.get(samples)
                            pcmChunks.add(samples)
                            totalSamplesDecoded += samples.size / channelCount
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }

            val mono = downmixToMono(pcmChunks, channelCount)
            resampleLinear(mono, nativeSampleRate, TARGET_SAMPLE_RATE)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to decode audio for tagging from $filePath", e)
            null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun downmixToMono(chunks: List<ShortArray>, channelCount: Int): FloatArray {
        val totalInterleavedSamples = chunks.sumOf { it.size }
        val frameCount = totalInterleavedSamples / channelCount
        val mono = FloatArray(frameCount)
        var frameIndex = 0
        for (chunk in chunks) {
            var i = 0
            while (i + channelCount <= chunk.size && frameIndex < frameCount) {
                var sum = 0f
                for (ch in 0 until channelCount) sum += chunk[i + ch]
                mono[frameIndex] = (sum / channelCount) / 32768f
                frameIndex++
                i += channelCount
            }
        }
        return mono
    }

    /** Simple linear-interpolation resampler -- adequate for tagging (not for playback-quality audio). */
    private fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val outputLength = ((input.size.toLong() * toRate) / fromRate).toInt()
        val output = FloatArray(outputLength)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (i in output.indices) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = (srcPos - srcIndex).toFloat()
            val a = input[min(srcIndex, input.size - 1)]
            val b = input[min(srcIndex + 1, input.size - 1)]
            output[i] = a + (b - a) * frac
        }
        return output
    }
}
