package com.example.tgmusicai.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.SongRecognitionManager
import com.example.tgmusicai.ui.viewmodel.RecognitionViewModel
import kotlin.math.roundToInt

/**
 * Listens through the microphone and reports which track in the library is playing.
 *
 * Says plainly what it can and cannot do. This matches against the user's own indexed library, not
 * a global catalogue -- identifying an unknown song against the world's music needs a commercial
 * fingerprinting service and a paid key, which this app deliberately does not carry. Presenting it
 * as a general "what song is this" and then failing on everything the user does not already own
 * would read as a broken feature rather than a deliberately scoped one.
 */
@Composable
fun RecognizeSongDialog(
    recognitionViewModel: RecognitionViewModel,
    onDismiss: () -> Unit,
    onPlaySong: (Song) -> Unit
) {
    val result by recognitionViewModel.result.collectAsState()
    val isListening by recognitionViewModel.isListening.collectAsState()
    val indexedCount by recognitionViewModel.indexedSongCount.collectAsState()

    // The permission is requested on open rather than behind a button: the dialog has exactly one
    // purpose, and an extra tap before it can do anything serves nobody.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) recognitionViewModel.listen()
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    AlertDialog(
        onDismissRequest = {
            recognitionViewModel.cancelListening()
            recognitionViewModel.clearResult()
            onDismiss()
        },
        title = { Text("What's playing?") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    isListening -> {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                        Text(
                            "Listening...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    result is SongRecognitionManager.Recognition.Found -> {
                        val found = result as SongRecognitionManager.Recognition.Found
                        Text(
                            text = found.song.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = found.song.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "About ${found.positionSeconds.roundToInt()}s in",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    result is SongRecognitionManager.Recognition.NotIndexed -> {
                        Text(
                            "No tracks are indexed yet. Build the recognition index in Settings, then try again.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    result is SongRecognitionManager.Recognition.CannotListen -> {
                        Text(
                            (result as SongRecognitionManager.Recognition.CannotListen).reason,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    result is SongRecognitionManager.Recognition.NoMatch -> {
                        Text(
                            "Couldn't place that one.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            // Explains the two quite different reasons for a miss, so the user
                            // knows whether trying again is worth it.
                            "This matches against the $indexedCount tracks in your own indexed library, not every song ever released. Either it isn't one of yours, or there was too much noise to hear it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            val found = result as? SongRecognitionManager.Recognition.Found
            if (found != null) {
                TextButton(onClick = {
                    onPlaySong(found.song)
                    recognitionViewModel.clearResult()
                    onDismiss()
                }) {
                    Text("Play")
                }
            } else if (!isListening) {
                TextButton(onClick = { recognitionViewModel.listen() }) {
                    Text("Try again")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                recognitionViewModel.cancelListening()
                recognitionViewModel.clearResult()
                onDismiss()
            }) {
                Text(if (isListening) "Cancel" else "Close")
            }
        }
    )
}
