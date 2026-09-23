package com.example.tgmusicai.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Decodes an audio file into 16kHz mono float PCM samples in `[-1, 1]`. Used only for on-device AI
 * inference (tagging, lyric transcription) -- never for actual playback -- so a decode failure
 * here has zero effect on the app's real audio pipeline. Uses the synchronous `MediaCodec` API,
 * which is fine for this one-shot batch decode.
 */
object PcmDecoder {
    private const val TAG = "PcmDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TIMEOUT_US = 10_000L

    // No practical song is anywhere near this long; it exists only to bound worst-case memory/time
    // if a corrupt file reports a bogus duration, not as a real expected ceiling.
    private const val MAX_FULL_DECODE_SEC = 1200

    /**
     * Decodes an entire audio file to mono 16kHz float PCM, for whole-track processing (on-device
     * lyric transcription -- see [com.example.tgmusicai.ai.WhisperTranscriptionEngine]) rather than
     * [decodeToMonoPcm16k]'s single representative sampling window. [mediaUri] may be a bare
     * filesystem path, a `file://` URI, or a `content://` URI (e.g. a device-scanned MediaStore
     * track) -- unlike [decodeToMonoPcm16k], this needs a [Context] to resolve the latter via
     * [android.content.ContentResolver] rather than relying on [MediaExtractor]'s plain-string
     * `setDataSource` to handle every URI scheme itself. Capped at [MAX_FULL_DECODE_SEC] as a
     * sanity bound, not a real-world limit.
     */
    fun decodeFullMonoPcm16k(context: Context, mediaUri: String): FloatArray? {
        var pfd: ParcelFileDescriptor? = null
        return try {
            val extractor = MediaExtractor()
            if (mediaUri.startsWith("content://")) {
                pfd = context.contentResolver.openFileDescriptor(Uri.parse(mediaUri), "r")
                    ?: return null.also { Log.w(TAG, "Could not open content URI $mediaUri") }
                extractor.setDataSource(pfd.fileDescriptor)
            } else {
                val path = if (mediaUri.startsWith("file://")) Uri.parse(mediaUri).path ?: mediaUri else mediaUri
                extractor.setDataSource(path)
            }
            decodeFromExtractor(extractor, mediaUri, MAX_FULL_DECODE_SEC)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to decode audio from $mediaUri", e)
            null
        } finally {
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /** Returns up to [maxDurationSec] seconds of mono 16kHz float PCM decoded from [filePath], or null on any failure. */
    fun decodeToMonoPcm16k(filePath: String, maxDurationSec: Int = 15): FloatArray? {
        return try {
            val extractor = MediaExtractor().apply { setDataSource(filePath) }
            decodeFromExtractor(extractor, filePath, maxDurationSec)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to decode audio for tagging from $filePath", e)
            null
        }
    }

    /** Shared decode loop for an already-`setDataSource`'d [extractor]; releases it (and the codec it creates) on every exit path. [logLabel] is only for error messages. */
    private fun decodeFromExtractor(extractor: MediaExtractor, logLabel: String, maxDurationSec: Int): FloatArray? {
        var codec: MediaCodec? = null
        return try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null.also { Log.w(TAG, "No audio track found in $logLabel") }

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val nativeSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(trackIndex)

            // Sample from ~25% into the track rather than position 0: many songs open with a
            // quiet/ambient intro that isn't representative of the track as a whole, which was
            // making tagging consistently under-confident. Falls back to position 0 for anything
            // too short for that to make sense. (For a full-track decode, maxDurationSec is large
            // enough that this condition never triggers for any real song -- see MAX_FULL_DECODE_SEC.)
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
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
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
