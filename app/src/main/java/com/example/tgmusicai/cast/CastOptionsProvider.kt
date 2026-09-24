package com.example.tgmusicai.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Configures the Cast framework. Play Services finds this class by name from the manifest, which
 * is why it must be public with a no-argument constructor.
 *
 * Uses the Default Media Receiver -- Google's own stock receiver app, which plays plain audio and
 * video URLs. A custom receiver would allow branding the screen shown on the TV, but it has to be
 * registered and hosted, and none of what this app casts needs behaviour the stock one lacks.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            // The framework otherwise stops the receiver whenever this app leaves the foreground,
            // which would end playback every time the user switched apps -- the opposite of what a
            // background music player wants.
            .setStopReceiverApplicationWhenEndingSession(false)
            .build()

    /** No additional session types beyond Cast itself. */
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
