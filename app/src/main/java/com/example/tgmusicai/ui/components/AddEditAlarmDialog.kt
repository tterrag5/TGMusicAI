package com.example.tgmusicai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.local.entity.Alarm
import com.example.tgmusicai.data.local.entity.AlarmToneType
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.data.local.entity.Song
import java.util.Calendar
import kotlin.math.roundToInt

/** Snooze length applied when the user turns snooze back on after disabling it. */
private const val DEFAULT_SNOOZE_MINUTES = 10

/**
 * Dialog for creating or editing a musical alarm with customizable time, repeat days, tone source, and snooze.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditAlarmDialog(
    initialAlarm: Alarm?,
    allSongs: List<Song>,
    allPlaylists: List<Playlist>,
    onDismiss: () -> Unit,
    onConfirm: (Alarm) -> Unit
) {
    val initialCal = Calendar.getInstance().apply {
        timeInMillis = initialAlarm?.timeInMillis ?: System.currentTimeMillis()
    }

    val timePickerState = rememberTimePickerState(
        initialHour = initialCal.get(Calendar.HOUR_OF_DAY),
        initialMinute = initialCal.get(Calendar.MINUTE),
        is24Hour = false
    )

    var label by remember { mutableStateOf(initialAlarm?.label ?: "Morning Alarm") }
    var toneType by remember { mutableStateOf(initialAlarm?.toneType ?: AlarmToneType.RANDOM_LIKED) }
    var toneUriOrId by remember { mutableStateOf(initialAlarm?.toneUriOrId ?: "") }
    var snoozeMinutes by remember { mutableIntStateOf(initialAlarm?.snoozeMinutes ?: 10) }
    var forceMaxVolume by remember { mutableStateOf(initialAlarm?.forceMaxVolume ?: false) }
    var volumeRampUp by remember { mutableStateOf(initialAlarm?.volumeRampUp ?: false) }

    val initialDaysSet = remember {
        initialAlarm?.repeatDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
    }
    var selectedDays by remember { mutableStateOf(initialDaysSet) }

    var toneDropdownExpanded by remember { mutableStateOf(false) }
    var showSongPicker by remember { mutableStateOf(false) }

    val dayNames = remember {
        listOf(
            1 to "Sun", 2 to "Mon", 3 to "Tue", 4 to "Wed",
            5 to "Thu", 6 to "Fri", 7 to "Sat"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialAlarm == null) "Create Alarm" else "Edit Alarm",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Time Picker
                TimePicker(
                    state = timePickerState,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Label TextField
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Alarm Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Repeat Days Selection
                Text(
                    text = "Repeat Days",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dayNames.forEach { (dayInt, dayName) ->
                        val isSelected = selectedDays.contains(dayInt)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedDays = if (isSelected) {
                                    selectedDays - dayInt
                                } else {
                                    selectedDays + dayInt
                                }
                            },
                            label = { Text(dayName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tone Type Radio Buttons
                Text(
                    text = "Alarm Tone Source",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Start)
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toneType = AlarmToneType.RANDOM_LIKED }
                    ) {
                        RadioButton(
                            selected = toneType == AlarmToneType.RANDOM_LIKED,
                            onClick = { toneType = AlarmToneType.RANDOM_LIKED }
                        )
                        Text("Random Liked / Most Played")
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toneType = AlarmToneType.SONG }
                    ) {
                        RadioButton(
                            selected = toneType == AlarmToneType.SONG,
                            onClick = { toneType = AlarmToneType.SONG }
                        )
                        Text("Specific Song")
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toneType = AlarmToneType.PLAYLIST }
                    ) {
                        RadioButton(
                            selected = toneType == AlarmToneType.PLAYLIST,
                            onClick = { toneType = AlarmToneType.PLAYLIST }
                        )
                        Text("Playlist")
                    }
                }

                // Tone Selector: song picker (searchable, cover art) or playlist dropdown
                if (toneType == AlarmToneType.SONG) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val selectedSong = allSongs.find { it.mediaUri == toneUriOrId || it.id.toString() == toneUriOrId }
                    Surface(
                        onClick = { showSongPicker = true },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!selectedSong?.artworkUri.isNullOrBlank()) {
                                    AsyncImage(
                                        model = FormatUtils.cacheBustedArtworkUri(selectedSong?.artworkUri),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedSong?.title ?: "Choose a song...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (selectedSong != null) {
                                    Text(
                                        text = selectedSong.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Choose a song",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (toneType == AlarmToneType.PLAYLIST) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = toneDropdownExpanded,
                        onExpandedChange = { toneDropdownExpanded = !toneDropdownExpanded }
                    ) {
                        val selectedPlaylist = allPlaylists.find { it.playlistId.toString() == toneUriOrId }
                        OutlinedTextField(
                            value = selectedPlaylist?.name ?: "Select a playlist...",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Selected Playlist") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = toneDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = toneDropdownExpanded,
                            onDismissRequest = { toneDropdownExpanded = false }
                        ) {
                            allPlaylists.forEach { playlist ->
                                DropdownMenuItem(
                                    text = { Text(playlist.name) },
                                    onClick = {
                                        toneUriOrId = playlist.playlistId.toString()
                                        toneDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Snooze duration: any whole number of minutes rather than four fixed chips, plus
                // an explicit off switch. 0 is the "snooze disabled" sentinel -- snoozeMinutes is
                // already a plain non-null Int column, so this needs no schema change, but every
                // consumer has to treat 0 as "no snooze" (see AlarmScheduler.scheduleSnooze,
                // AlarmActivity's snooze button and AlarmItem's summary line).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            snoozeMinutes = if (snoozeMinutes == 0) DEFAULT_SNOOZE_MINUTES else 0
                        }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = if (snoozeMinutes == 0) "Snooze off" else "Snooze: $snoozeMinutes min",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = snoozeMinutes > 0,
                        onCheckedChange = { enabled ->
                            snoozeMinutes = if (enabled) DEFAULT_SNOOZE_MINUTES else 0
                        }
                    )
                }

                if (snoozeMinutes > 0) {
                    Slider(
                        value = snoozeMinutes.toFloat(),
                        onValueChange = { snoozeMinutes = it.roundToInt().coerceAtLeast(1) },
                        valueRange = 1f..60f,
                        steps = 58,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Volume Behavior -- per-alarm now rather than one global setting applied to
                // every alarm, so a loud "force max volume" wake-up and a gentle unforced one can
                // coexist as different alarms.
                Text(
                    text = "Volume",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Start)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { forceMaxVolume = !forceMaxVolume }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        "Force max alarm volume",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = forceMaxVolume, onCheckedChange = { forceMaxVolume = it })
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { volumeRampUp = !volumeRampUp }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        "Gradually increase volume",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = volumeRampUp, onCheckedChange = { volumeRampUp = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }

                    val repeatDaysStr = selectedDays.sorted().joinToString(",")

                    val updatedAlarm = Alarm(
                        id = initialAlarm?.id ?: 0L,
                        timeInMillis = cal.timeInMillis,
                        isEnabled = true,
                        repeatDays = repeatDaysStr,
                        toneType = toneType,
                        toneUriOrId = toneUriOrId,
                        snoozeMinutes = snoozeMinutes,
                        label = label,
                        forceMaxVolume = forceMaxVolume,
                        volumeRampUp = volumeRampUp
                    )
                    onConfirm(updatedAlarm)
                }
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    if (showSongPicker) {
        SongPickerDialog(
            allSongs = allSongs,
            onDismissRequest = { showSongPicker = false },
            onSongSelected = { song -> toneUriOrId = song.mediaUri }
        )
    }
}
