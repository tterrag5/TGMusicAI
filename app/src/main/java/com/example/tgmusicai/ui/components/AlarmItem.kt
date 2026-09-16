package com.example.tgmusicai.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tgmusicai.data.local.entity.Alarm
import com.example.tgmusicai.data.local.entity.AlarmToneType
import com.example.tgmusicai.ui.util.FormatUtils

/**
 * Expressive Material 3 Card component displaying an alarm's details, toggle status, and actions.
 */
@Composable
fun AlarmItem(
    alarm: Alarm,
    onToggle: (Alarm) -> Unit,
    onEdit: (Alarm) -> Unit,
    onDelete: (Alarm) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEdit(alarm) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.isEnabled) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = FormatUtils.formatTimeOfDay(alarm.timeInMillis),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (alarm.isEnabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))

                    val toneIcon = when (alarm.toneType) {
                        AlarmToneType.SONG -> Icons.Rounded.MusicNote
                        AlarmToneType.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
                        AlarmToneType.RANDOM_LIKED -> Icons.Rounded.Shuffle
                    }

                    Icon(
                        imageVector = toneIcon,
                        contentDescription = "Tone Type",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = alarm.label.ifBlank { "Alarm" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(4.dp))

                val repeatSummary = formatRepeatDaysSummary(alarm.repeatDays)
                Text(
                    text = "$repeatSummary • Snooze: ${alarm.snoozeMinutes}m",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onEdit(alarm) }) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Edit Alarm",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = { onDelete(alarm) }) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete Alarm",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = { onToggle(alarm) }
                )
            }
        }
    }
}

/**
 * Formats day string e.g. "1,2,3,4,5,6,7" into human readable summary.
 */
private fun formatRepeatDaysSummary(repeatDaysStr: String): String {
    if (repeatDaysStr.isBlank()) return "Once"
    val daysList = repeatDaysStr.split(",").mapNotNull { it.trim().toIntOrNull() }
    if (daysList.size == 7) return "Everyday"
    if (daysList.containsAll(listOf(2, 3, 4, 5, 6)) && daysList.size == 5) return "Weekdays"
    if (daysList.containsAll(listOf(1, 7)) && daysList.size == 2) return "Weekends"

    val dayNames = mapOf(
        1 to "Sun", 2 to "Mon", 3 to "Tue", 4 to "Wed",
        5 to "Thu", 6 to "Fri", 7 to "Sat"
    )
    return daysList.mapNotNull { dayNames[it] }.joinToString(", ")
}
