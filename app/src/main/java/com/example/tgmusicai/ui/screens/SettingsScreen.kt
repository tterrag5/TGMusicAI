package com.example.tgmusicai.ui.screens

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tgmusicai.data.sponsorblock.SponsorBlockManager
import com.example.tgmusicai.ui.theme.AppTheme
import com.example.tgmusicai.ui.theme.ThemeMode
import com.example.tgmusicai.ui.viewmodel.HomeViewModel
import com.example.tgmusicai.ui.viewmodel.PlayerViewModel

/**
 * Unified Settings hub consolidating what used to be scattered across HomeScreen's top bar
 * (theme switcher, export, restore): Appearance (light/dark, palette, Material You), Playback
 * behavior, and Backup & Restore, all in one place reachable from the navigation drawer. Alarm sound behavior (force max volume / gradual ramp-up) used to live
 * here as one global setting applied to every alarm; it's now chosen per-alarm in
 * [com.example.tgmusicai.ui.components.AddEditAlarmDialog] instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    homeViewModel: HomeViewModel,
    playerViewModel: PlayerViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backupStatus by homeViewModel.backupStatus.collectAsState()
    val isBackupLoading by homeViewModel.isBackupLoading.collectAsState()
    // Read directly from the ViewModel's StateFlow (not threaded through as a plain parameter)
    // so this screen's own recomposition reflects a change immediately -- see
    // PlayerViewModel.selectedTheme's doc comment for why a threaded-through parameter went stale.
    val currentTheme by playerViewModel.selectedTheme.collectAsState()
    val onSelectTheme: (String) -> Unit = playerViewModel::setSelectedTheme
    val themeMode by playerViewModel.themeMode.collectAsState()
    val dynamicColorEnabled by playerViewModel.dynamicColorEnabled.collectAsState()
    val skipSilenceEnabled by playerViewModel.skipSilenceEnabled.collectAsState()
    val volumeNormalizationEnabled by playerViewModel.volumeNormalizationEnabled.collectAsState()
    val sponsorBlockEnabled by playerViewModel.sponsorBlockEnabled.collectAsState()
    val sponsorBlockCategories by playerViewModel.sponsorBlockCategories.collectAsState()
    val crossfadeEnabled by playerViewModel.crossfadeEnabled.collectAsState()
    val crossfadeDurationSec by playerViewModel.crossfadeDurationSec.collectAsState()
    var draggedCrossfadeDurationSec by remember(crossfadeDurationSec) { mutableStateOf(crossfadeDurationSec.toFloat()) }

    // Only one section is open at a time and everything starts collapsed, so all of Settings is
    // visible at once and picking a theme no longer means scrolling past every other setting.
    var expandedSection by remember { mutableStateOf<SettingsSectionId?>(null) }

    val appearanceSummary = if (dynamicColorEnabled) {
        "${ThemeMode.fromName(themeMode).displayName} - Material You"
    } else {
        "${ThemeMode.fromName(themeMode).displayName} - ${AppTheme.fromName(currentTheme).displayName}"
    }
    val playbackSummary = listOfNotNull(
        "Normalize volume".takeIf { volumeNormalizationEnabled },
        "Skip silence".takeIf { skipSilenceEnabled },
        "Crossfade".takeIf { crossfadeEnabled },
        "Skip non-music".takeIf { sponsorBlockEnabled }
    ).joinToString(", ").ifEmpty { "Default playback behavior" }

    // CreateDocument gives the user a real "save as" dialog and hands back a writable URI, so
    // export needs no storage permission. The MIME type is the generic binary one because
    // .tgmusic isn't a registered type; the suggested file name carries the extension.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { homeViewModel.exportBackup(context, it) }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            if (inputStream != null) {
                homeViewModel.importBackup(context, inputStream)
            }
        }
    }

    LaunchedEffect(backupStatus) {
        backupStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            homeViewModel.clearBackupStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding),
            // Bottom padding clears the bottom navigation bar MainScreen draws over this screen --
            // without it the last settings card was cut off and unreachable.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsSection(
                    sectionId = SettingsSectionId.APPEARANCE,
                    title = "Appearance",
                    icon = Icons.Rounded.Palette,
                    summary = appearanceSummary,
                    expanded = expandedSection == SettingsSectionId.APPEARANCE,
                    onToggle = { expandedSection = it }
                ) {
                    ThemeModeCard(
                        selectedMode = themeMode,
                        onSelectMode = playerViewModel::setThemeMode
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ThemePaletteCard(
                        selectedTheme = currentTheme,
                        dynamicColorEnabled = dynamicColorEnabled,
                        onSelectTheme = onSelectTheme
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Spacer(modifier = Modifier.height(12.dp))
                        SettingsToggleRow(
                            title = "Material You colors",
                            subtitle = "Use your wallpaper's colors instead of the palette above",
                            checked = dynamicColorEnabled,
                            onCheckedChange = playerViewModel::setDynamicColorEnabled
                        )
                    }
                }
            }

            item {
                SettingsSection(
                    sectionId = SettingsSectionId.PLAYBACK,
                    title = "Playback",
                    icon = Icons.Rounded.PlayCircle,
                    summary = playbackSummary,
                    expanded = expandedSection == SettingsSectionId.PLAYBACK,
                    onToggle = { expandedSection = it }
                ) {
                    SettingsToggleRow(
                        title = "Normalize volume",
                        subtitle = "Play every track at the same loudness, so a quiet local file and a loud YouTube stream don't jump in volume.",
                        checked = volumeNormalizationEnabled,
                        onCheckedChange = playerViewModel::setVolumeNormalizationEnabled
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingsToggleRow(
                        title = "Skip silence",
                        subtitle = "Auto-trim dead silence at the start/end of tracks, common on YouTube-sourced audio.",
                        checked = skipSilenceEnabled,
                        onCheckedChange = playerViewModel::setSkipSilenceEnabled
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SettingsToggleContent(
                                title = "Crossfade",
                                subtitle = "Fade a track out as it ends and fade the next one in, instead of cutting abruptly.",
                                checked = crossfadeEnabled,
                                onCheckedChange = playerViewModel::setCrossfadeEnabled
                            )
                            if (crossfadeEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Fade duration: ${draggedCrossfadeDurationSec.toInt()}s",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = draggedCrossfadeDurationSec,
                                    onValueChange = { draggedCrossfadeDurationSec = it },
                                    onValueChangeFinished = {
                                        playerViewModel.setCrossfadeDurationSec(draggedCrossfadeDurationSec.toInt())
                                    },
                                    valueRange = 1f..12f,
                                    steps = 10
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SettingsToggleContent(
                                title = "Skip non-music sections",
                                subtitle = "Use the community SponsorBlock database to jump past intros, sponsor reads and talking in YouTube tracks. Sends an anonymised lookup per track.",
                                checked = sponsorBlockEnabled,
                                onCheckedChange = playerViewModel::setSponsorBlockEnabled
                            )
                            if (sponsorBlockEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "What to skip",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                SponsorBlockManager.CATEGORY_LABELS.forEach { (category, label) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = category in sponsorBlockCategories,
                                            onCheckedChange = { playerViewModel.toggleSponsorBlockCategory(category, it) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(
                    sectionId = SettingsSectionId.BACKUP,
                    title = "Backup & Restore",
                    icon = Icons.Rounded.CloudSync,
                    summary = if (isBackupLoading) "Working..." else "Export or restore your library",
                    expanded = expandedSection == SettingsSectionId.BACKUP,
                    onToggle = { expandedSection = it }
                ) {
                    SettingsRow(
                        icon = Icons.Rounded.Save,
                        title = "Export Backup",
                        subtitle = "Save your library, playlists & stats as a .tgmusic file",
                        enabled = !isBackupLoading,
                        onClick = {
                            exportLauncher.launch("TGMusic_Backup_${System.currentTimeMillis()}.tgmusic")
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingsRow(
                        icon = Icons.Rounded.Restore,
                        title = "Restore Backup",
                        subtitle = "Import a previously exported .tgmusic file",
                        enabled = !isBackupLoading,
                        onClick = { restoreLauncher.launch("*/*") }
                    )
                    if (isBackupLoading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Processing backup / restore...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

        }
    }
}

/**
 * The collapsible groups on the Settings screen.
 *
 * Passed explicitly to [SettingsSection] rather than derived from the section's display title:
 * matching on the title meant renaming a heading silently re-pointed it at another section's
 * expand state, and every new section defaulted into whichever branch the `else` happened to name.
 */
private enum class SettingsSectionId { APPEARANCE, PLAYBACK, SCROBBLING, BACKUP }

/**
 * A collapsible Settings group: a tappable header showing the section name and its current state,
 * which expands to reveal [content].
 *
 * Settings used to render every control of every section inline, so reaching the last one meant
 * scrolling through all of them. Collapsed-by-default sections put the whole menu on one screen and
 * let the header's [summary] answer "what is this set to?" without opening anything.
 */
@Composable
private fun SettingsSection(
    sectionId: SettingsSectionId,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    summary: String,
    expanded: Boolean,
    onToggle: (SettingsSectionId?) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settingsSectionChevron"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { onToggle(if (expanded) null else sectionId) },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f, fill = true)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation)
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                content()
            }
        }
    }
}

/**
 * Label-plus-switch row body. Every toggle in Settings goes through this so the label always gets
 * the leftover width and the switch keeps its intrinsic size -- hand-rolling the same Row per
 * setting is how a switch ends up sitting on top of a long label.
 */
@Composable
private fun SettingsToggleContent(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .padding(end = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** [SettingsToggleContent] wrapped in the standard Settings card. */
@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            SettingsToggleContent(title, subtitle, checked, onCheckedChange)
        }
    }
}

/** Light / Dark / Follow-system selector. */
@Composable
private fun ThemeModeCard(
    selectedMode: String,
    onSelectMode: (String) -> Unit
) {
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Light & dark", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = ThemeMode.fromName(selectedMode) == mode,
                        onClick = { onSelectMode(mode.name) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size)
                    ) {
                        Text(mode.displayName, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * Palette picker. Each entry shows the theme's actual accent color, so the list reads as a set of
 * colors rather than a list of names.
 */
@Composable
private fun ThemePaletteCard(
    selectedTheme: String,
    dynamicColorEnabled: Boolean,
    onSelectTheme: (String) -> Unit
) {
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Color palette", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (dynamicColorEnabled) {
                Text(
                    "Material You is on, so these palettes aren't being applied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            AppTheme.entries.forEach { theme ->
                val isSelected = AppTheme.fromName(selectedTheme) == theme
                Surface(
                    onClick = { onSelectTheme(theme.name) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(theme.accentPreview)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = theme.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Currently selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
