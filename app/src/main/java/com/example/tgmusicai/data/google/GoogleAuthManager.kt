package com.example.tgmusicai.data.google

import android.content.IntentSender
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps Google Play Services' Authorization API ([com.google.android.gms.auth.api.identity.AuthorizationClient])
 * to obtain an OAuth access token scoped to the YouTube Data API, without needing a backend
 * server (the app's OAuth client is registered as "Android" type in Google Cloud Console, a
 * public client tied to the app's package name + signing certificate).
 *
 * Must be constructed in [ComponentActivity.onCreate] (before the activity reaches STARTED),
 * since it registers an [androidx.activity.result.ActivityResultLauncher] for the consent screen.
 */
class GoogleAuthManager(private val activity: ComponentActivity) {

    companion object {
        private const val TAG = "GoogleAuthManager"

        /**
         * Read-only YouTube Data API scope: lets the app list/read the signed-in user's
         * playlists, liked videos and subscriptions, but not modify them. This is the only
         * scope requested by [getAccessToken]'s default, since importing playlists never
         * needs write access.
         */
        const val YOUTUBE_READONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    }

    /**
     * Non-null only while a consent screen launched by [consentLauncher] is in flight. Completed
     * (with `null`) once the user responds, regardless of whether they granted or cancelled --
     * the actual outcome is read back from [pendingConsentIntent] afterward.
     */
    private var pendingConsent: CompletableDeferred<IntentSender.SendIntentException?>? = null

    /** Result [android.content.Intent] from the most recently finished consent screen. */
    private var pendingConsentIntent: android.content.Intent? = null

    /**
     * Launcher for Google's account/consent picker, shown by [getAccessToken] only when the
     * requested scopes haven't already been granted. Must be registered before the host
     * [ComponentActivity] reaches STARTED, which is why this class has to be constructed in
     * `onCreate`.
     */
    private val consentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        pendingConsentIntent = result.data
        pendingConsent?.complete(null)
    }

    /**
     * Returns a valid OAuth access token for [scopes], prompting the user for consent via the
     * Google account picker/consent screen only if not already granted. Returns null on failure
     * or if the user cancels consent.
     */
    suspend fun getAccessToken(scopes: List<String> = listOf(YOUTUBE_READONLY_SCOPE)): String? {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(scopes.map { Scope(it) })
                .build()

            val authorizationClient = Identity.getAuthorizationClient(activity)
            var result = awaitTask(authorizationClient.authorize(request))

            if (result.hasResolution()) {
                val pendingIntent = result.pendingIntent ?: return null
                val deferred = CompletableDeferred<IntentSender.SendIntentException?>()
                pendingConsent = deferred
                pendingConsentIntent = null
                consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                deferred.await()
                pendingConsent = null

                val data = pendingConsentIntent ?: return null
                result = authorizationClient.getAuthorizationResultFromIntent(data)
            }

            result.accessToken
        } catch (e: ApiException) {
            Log.e(TAG, "Authorization failed: ${e.statusCode} ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Authorization failed: ${e.message}", e)
            null
        }
    }

    /**
     * Bridges a Play Services [Task] (which reports results via callbacks) into a coroutine
     * suspend point, resuming with the task's result or throwing its exception.
     */
    private suspend fun awaitTask(task: Task<AuthorizationResult>): AuthorizationResult =
        suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { result -> continuation.resume(result) }
            task.addOnFailureListener { exception -> continuation.resumeWithException(exception) }
        }
}
