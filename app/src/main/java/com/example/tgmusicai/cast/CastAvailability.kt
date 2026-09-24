package com.example.tgmusicai.cast

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Obtains the Cast framework, or reports that this device cannot use it.
 *
 * Cast is built on Google Play Services, which is absent on a meaningful share of Android devices
 * -- de-Googled ROMs, some Chinese OEM builds, emulators without Play. On those,
 * `CastContext.getSharedInstance` throws rather than returning null, and an unguarded call takes
 * down whatever was initialising it. Every entry point here goes through one guarded accessor so
 * the rest of the app can treat Cast as simply unavailable.
 */
object CastAvailability {

    private const val TAG = "CastAvailability"

    @Volatile
    private var castContext: CastContext? = null

    @Volatile
    private var knownUnavailable = false

    /**
     * The shared [CastContext], or null if Cast cannot be used here.
     *
     * A failure is remembered rather than retried: it means Play Services is missing or too old,
     * which will not change while the app is running, and retrying would repeat an expensive
     * initialisation on every playback event.
     */
    fun castContext(context: Context): CastContext? {
        if (knownUnavailable) return null
        castContext?.let { return it }

        return synchronized(this) {
            if (knownUnavailable) return@synchronized null
            castContext?.let { return@synchronized it }

            val playServicesStatus = try {
                GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(context.applicationContext)
            } catch (e: Throwable) {
                Log.d(TAG, "Could not query Play Services availability", e)
                ConnectionResult.SERVICE_MISSING
            }

            if (playServicesStatus != ConnectionResult.SUCCESS) {
                Log.d(TAG, "Cast unavailable: Play Services status $playServicesStatus")
                knownUnavailable = true
                return@synchronized null
            }

            try {
                CastContext.getSharedInstance(context.applicationContext).also { castContext = it }
            } catch (e: Throwable) {
                Log.d(TAG, "Cast unavailable: ${e.message}")
                knownUnavailable = true
                null
            }
        }
    }

    /** Whether Cast can be used, without forcing initialisation if it already failed once. */
    fun isAvailable(context: Context): Boolean = castContext(context) != null
}
