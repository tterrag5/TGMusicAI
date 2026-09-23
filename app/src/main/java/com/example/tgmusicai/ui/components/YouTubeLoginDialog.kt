package com.example.tgmusicai.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tgmusicai.data.youtube.InnerTubeCookieManager
import com.example.tgmusicai.data.youtube.YOUTUBE_MUSIC_ORIGIN
import kotlinx.coroutines.launch

/**
 * Full-screen dialog hosting a real [WebView] signed into music.youtube.com -- the in-app
 * replacement for the old Google OAuth consent screen. After every page load, the WebView's
 * accumulated cookies are checked via [InnerTubeCookieManager.captureFromWebView]; the moment a
 * usable session shows up (i.e. the user finished signing in), [onSignedIn] fires and the caller
 * is expected to dismiss the dialog -- there's no separate "Done" button to press.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLoginDialog(
    cookieManager: InnerTubeCookieManager,
    onSignedIn: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isPageLoading by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sign in to YouTube Music") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Cancel")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // Start from this device's real WebView User-Agent and remove only the
                            // "; wv" marker that identifies it as a WebView, rather than claiming to
                            // be desktop Chrome on Windows. A hardcoded desktop UA contradicts
                            // everything else the WebView reports about itself (client hints still
                            // say Android, the platform is mobile), and that mismatch is exactly
                            // what Google's sign-in flow rejects with "This browser or app may not
                            // be secure". Keeping the genuine Android Chrome UA minus the marker
                            // leaves a self-consistent browser identity.
                            settings.userAgentString = WebSettings.getDefaultUserAgent(context)
                                .replace("; wv", "")
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            // WebView auto-sends an `X-Requested-With: <package name>` header on every
                            // request identifying itself as an embedded WebView. Google's sign-in flow
                            // checks for this header and blocks the login with "This browser or app may
                            // not be secure" regardless of the spoofed User-Agent above. Clearing the
                            // allow-list removes the header entirely so Google treats this like a normal
                            // browser request.
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isPageLoading = false
                                    // Checked after every navigation (sign-in is itself a chain of
                                    // redirects across accounts.google.com back to music.youtube.com)
                                    // so this fires as soon as a usable session actually exists,
                                    // without the user having to signal completion themselves.
                                    scope.launch {
                                        if (cookieManager.captureFromWebView()) {
                                            onSignedIn()
                                        }
                                    }
                                }
                            }
                            loadUrl("$YOUTUBE_MUSIC_ORIGIN/")
                        }
                    },
                    // Without this, dismissing the dialog detaches the WebView from the
                    // composition but never releases its internal resources (render process,
                    // native memory) -- destroy() is the only way to actually free them.
                    onRelease = { it.destroy() }
                )
                if (isPageLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}
