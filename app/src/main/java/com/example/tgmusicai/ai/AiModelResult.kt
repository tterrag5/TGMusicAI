package com.example.tgmusicai.ai

/**
 * Result of any on-device AI inference call in this package. Every engine returns this instead of
 * throwing, and every facade method ([AiFeatureManager]) catches at its own boundary too -- so a
 * broken model file, an incompatible device, or a bad input can only ever surface as [Unavailable]
 * or [Error] to the rest of the app, never as an uncaught exception.
 */
sealed class AiModelResult<out T> {
    data class Success<T>(val value: T) : AiModelResult<T>()
    /** The feature isn't usable right now (model missing/failed to load, no session yet, etc.) -- not an error, just off. */
    data class Unavailable(val reason: String) : AiModelResult<Nothing>()
    /** Inference itself threw for this specific call. The engine may still be usable for the next call. */
    data class Error(val throwable: Throwable) : AiModelResult<Nothing>()

    fun getOrNull(): T? = (this as? Success<T>)?.value
}
