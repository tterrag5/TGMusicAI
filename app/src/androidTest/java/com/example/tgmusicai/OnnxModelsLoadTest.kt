package com.example.tgmusicai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every bundled ONNX model opens in the ONNX Runtime the app ships (T028: bumped 1.19.2 -> 1.29.0
 * for 16 KB page alignment). A runtime bump that drops an operator a model uses fails here, on a
 * device, instead of silently disabling the AI feature behind it.
 */
@RunWith(AndroidJUnit4::class)
class OnnxModelsLoadTest {

    @Test
    fun everyBundledModelLoads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = OrtEnvironment.getEnvironment()
        val models = listOf(
            "ai/minilm_quantized.onnx",
            "ai/effnet_discogs.onnx",
            "ai/whisper/encoder_model.onnx",
            "ai/whisper/decoder_model_merged.onnx",
        )
        for (asset in models) {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            env.createSession(bytes, OrtSession.SessionOptions()).use { session ->
                assertTrue("$asset has no inputs", session.inputNames.isNotEmpty())
                assertTrue("$asset has no outputs", session.outputNames.isNotEmpty())
            }
        }
    }
}
