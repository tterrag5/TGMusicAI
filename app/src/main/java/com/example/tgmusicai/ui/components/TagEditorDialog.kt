package com.example.tgmusicai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tgmusicai.data.local.AudioTagIo
import com.example.tgmusicai.data.local.entity.Song

/**
 * Edits the metadata stored inside a local audio file.
 *
 * Fields are pre-filled from the file's own tags rather than from the library row. The two can
 * differ -- the library's title has been through [com.example.tgmusicai.data.local.AiMetadataCleaner],
 * which strips things like "(Official Audio)" -- and showing the cleaned-up version would mean the
 * user saves it straight back into the file, quietly rewriting tags they never chose to change.
 * Where the file has no tag at all, the library value fills in as a starting point.
 */
@Composable
fun TagEditorDialog(
    song: Song,
    loadTags: suspend (Song) -> AudioTagIo.EditableTags?,
    onDismiss: () -> Unit,
    onSave: (AudioTagIo.EditableTags) -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var albumArtist by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var trackNumber by remember { mutableStateOf("") }

    LaunchedEffect(song.id) {
        val fileTags = loadTags(song)
        title = fileTags?.title ?: song.title
        artist = fileTags?.artist ?: song.artist
        album = fileTags?.album ?: song.album
        albumArtist = fileTags?.albumArtist.orEmpty()
        genre = fileTags?.genre.orEmpty()
        year = fileTags?.year.orEmpty()
        trackNumber = fileTags?.trackNumber.orEmpty()
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit tags") },
        text = {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Changes are written into the audio file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TagField("Title", title) { title = it }
                    TagField("Artist", artist) { artist = it }
                    TagField("Album", album) { album = it }
                    TagField("Album artist", albumArtist) { albumArtist = it }
                    TagField("Genre", genre) { genre = it }
                    TagField("Year", year) { year = it }
                    TagField("Track number", trackNumber) { trackNumber = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && title.isNotBlank(),
                onClick = {
                    onSave(
                        AudioTagIo.EditableTags(
                            title = title,
                            artist = artist,
                            album = album,
                            albumArtist = albumArtist,
                            genre = genre,
                            year = year,
                            trackNumber = trackNumber
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun TagField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
