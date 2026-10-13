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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.selection.SelectionContainer
import com.example.tgmusicai.BuildConfig
import com.example.tgmusicai.data.repository.VarietyMode
import com.example.tgmusicai.data.sponsorblock.SponsorBlockManager
import com.example.tgmusicai.ui.theme.AppTheme
import com.example.tgmusicai.ui.components.scene.ThemeBackdrop
import androidx.compose.ui.graphics.luminance
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
    recognitionViewModel: com.example.tgmusicai.ui.viewmodel.RecognitionViewModel? = null,
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
    val sceneMotion by playerViewModel.sceneMotionEnabled.collectAsState()
    val skipSilenceEnabled by playerViewModel.skipSilenceEnabled.collectAsState()
    val volumeNormalizationEnabled by playerViewModel.volumeNormalizationEnabled.collectAsState()
    val sponsorBlockEnabled by playerViewModel.sponsorBlockEnabled.collectAsState()
    val sponsorBlockCategories by playerViewModel.sponsorBlockCategories.collectAsState()
    val scrobblingEnabled by playerViewModel.scrobblingEnabled.collectAsState()
    val listenBrainzUsername by playerViewModel.listenBrainzUsername.collectAsState()
    val pendingScrobbleCount by playerViewModel.pendingScrobbleCount.collectAsState()
    val listenBrainzServer by playerViewModel.listenBrainzServer.collectAsState()
    val scrobbleConnectionStatus by playerViewModel.scrobbleConnectionStatus.collectAsState()
    val isConnectingScrobbler by playerViewModel.isConnectingScrobbler.collectAsState()
    var listenBrainzTokenInput by remember { mutableStateOf("") }
    val isYouTubeSignedIn by playerViewModel.isYouTubeSignedIn.collectAsState()
    val youtubeHistoryEnabled by playerViewModel.youtubeHistoryEnabled.collectAsState()
    val hasLastFmApiKey by playerViewModel.hasLastFmApiKey.collectAsState()
    val lastFmRecommendationsEnabled by playerViewModel.lastFmRecommendationsEnabled.collectAsState()
    val isCheckingLastFmKey by playerViewModel.isCheckingLastFmKey.collectAsState()
    val lastFmKeyStatus by playerViewModel.lastFmKeyStatus.collectAsState()
    var lastFmKeyInput by remember { mutableStateOf("") }
    var listenBrainzServerInput by remember(listenBrainzServer) { mutableStateOf(listenBrainzServer) }
    val crossfadeEnabled by playerViewModel.crossfadeEnabled.collectAsState()
    val lyricsProviders by playerViewModel.lyricsProviders.collectAsState()
    val radioVariety by playerViewModel.radioVariety.collectAsState()
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

    LaunchedEffect(scrobbleConnectionStatus) {
        scrobbleConnectionStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            playerViewModel.clearScrobbleConnectionStatus()
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
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                        sceneMotion = sceneMotion,
                        onSceneMotionChange = playerViewModel::setSceneMotionEnabled,
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
                        subtitle = "Play every track at the same loudness.",
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
                                subtitle = "Fade between tracks.",
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
                            Text(
                                "Radio variety",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "How far Start Radio ranges from the artists you already play.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                VarietyMode.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = radioVariety == mode,
                                        onClick = { playerViewModel.setRadioVariety(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = VarietyMode.entries.size,
                                        ),
                                    ) {
                                        Text(mode.displayName, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (hasLastFmApiKey) {
                                SettingsToggleContent(
                                    title = "Last.fm recommendations",
                                    subtitle = "Add tracks Last.fm lists as similar to your recent listening to recommendations and autoplay.",
                                    checked = lastFmRecommendationsEnabled,
                                    onCheckedChange = playerViewModel::setLastFmRecommendationsEnabled
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = playerViewModel::removeLastFmKey) {
                                    Text("Remove key")
                                }
                            } else {
                                Text(
                                    "Last.fm recommendations",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Uses your own Last.fm API key. Sends the artist and title of recently played songs to Last.fm.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = lastFmKeyInput,
                                    onValueChange = { lastFmKeyInput = it },
                                    label = { Text("API key") },
                                    supportingText = lastFmKeyStatus?.let { status -> { Text(status) } },
                                    isError = lastFmKeyStatus != null,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    enabled = lastFmKeyInput.isNotBlank() && !isCheckingLastFmKey,
                                    onClick = {
                                        playerViewModel.connectLastFm(lastFmKeyInput)
                                    }
                                ) {
                                    Text(if (isCheckingLastFmKey) "Checking..." else "Save key")
                                }
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
                                subtitle = "Jump past intros, sponsor reads and talking in YouTube tracks. Sends an anonymised lookup per track.",
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
                    sectionId = SettingsSectionId.LYRICS,
                    title = "Lyrics",
                    icon = Icons.Rounded.Lyrics,
                    summary = lyricsProviders.filter { it.enabled }.joinToString(" > ") { it.provider.label },
                    expanded = expandedSection == SettingsSectionId.LYRICS,
                    onToggle = { expandedSection = it }
                ) {
                    LyricsProviderList(
                        settings = lyricsProviders,
                        onChange = playerViewModel::setLyricsProviders,
                    )
                }
            }

            if (recognitionViewModel != null) {
                item {
                    val indexedCount by recognitionViewModel.indexedSongCount.collectAsState()
                    val isIndexing by recognitionViewModel.isIndexing.collectAsState()
                    SettingsSection(
                        sectionId = SettingsSectionId.RECOGNITION,
                        title = "Song recognition",
                        icon = Icons.Rounded.Mic,
                        summary = when {
                            isIndexing -> "Indexing... $indexedCount tracks so far"
                            indexedCount > 0 -> "$indexedCount tracks indexed"
                            else -> "Not set up"
                        },
                        expanded = expandedSection == SettingsSectionId.RECOGNITION,
                        onToggle = { expandedSection = it }
                    ) {
                        Card(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    // Scope, not rationale: it matters to the user that this
                                    // recognises their own library and nothing else.
                                    "Identifies tracks from your own library by listening. Works offline.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Indexed: $indexedCount tracks",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    // A storage cost the user should be able to decline knowingly.
                                    "Roughly 1-2 MB per song. Can be cleared at any time.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row {
                                    if (isIndexing) {
                                        TextButton(onClick = recognitionViewModel::cancelIndexing) {
                                            Text("Stop indexing")
                                        }
                                    } else {
                                        TextButton(onClick = recognitionViewModel::buildIndex) {
                                            Text(if (indexedCount > 0) "Index new tracks" else "Build index")
                                        }
                                    }
                                    if (indexedCount > 0) {
                                        TextButton(onClick = recognitionViewModel::clearIndex) {
                                            Text("Clear index")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(
                    sectionId = SettingsSectionId.SCROBBLING,
                    title = "Listening history",
                    icon = Icons.Rounded.History,
                    summary = when {
                        scrobblingEnabled && listenBrainzUsername != null ->
                            listOfNotNull(
                                "Scrobbling as $listenBrainzUsername",
                                // Only when there is something waiting: a count of zero is noise.
                                pendingScrobbleCount
                                    .takeIf { it > 0 }
                                    ?.let { "$it waiting to send" },
                            ).joinToString(" \u00b7 ")
                        listenBrainzUsername != null -> "Connected, but paused"
                        else -> "Not connected"
                    },
                    expanded = expandedSection == SettingsSectionId.SCROBBLING,
                    onToggle = { expandedSection = it }
                ) {
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Submit finished tracks to your ListenBrainz account.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            if (listenBrainzUsername != null) {
                                SettingsToggleContent(
                                    title = "Scrobble to ListenBrainz",
                                    subtitle = "Connected as $listenBrainzUsername",
                                    checked = scrobblingEnabled,
                                    onCheckedChange = playerViewModel::setScrobblingEnabled
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = playerViewModel::disconnectListenBrainz) {
                                    Text("Disconnect")
                                }
                            } else {
                                OutlinedTextField(
                                    value = listenBrainzTokenInput,
                                    onValueChange = { listenBrainzTokenInput = it },
                                    label = { Text("User token") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = listenBrainzServerInput,
                                    onValueChange = { listenBrainzServerInput = it },
                                    label = { Text("Server") },
                                    supportingText = { Text("Leave as-is unless you run your own server.") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    enabled = listenBrainzTokenInput.isNotBlank() && !isConnectingScrobbler,
                                    onClick = {
                                        playerViewModel.connectListenBrainz(
                                            listenBrainzTokenInput,
                                            listenBrainzServerInput
                                        )
                                    }
                                ) {
                                    Text(if (isConnectingScrobbler) "Checking..." else "Connect")
                                }
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
                                title = "Add to YouTube history",
                                subtitle = if (isYouTubeSignedIn) {
                                    "Add YouTube songs you play here to your YouTube Music history."
                                } else {
                                    "Sign in to YouTube (Import from YouTube) to use this."
                                },
                                checked = youtubeHistoryEnabled && isYouTubeSignedIn,
                                onCheckedChange = { enabled ->
                                    if (isYouTubeSignedIn) playerViewModel.setYouTubeHistoryEnabled(enabled)
                                }
                            )
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

                Spacer(modifier = Modifier.height(12.dp))

                SettingsSection(
                    sectionId = SettingsSectionId.ABOUT,
                    title = "About",
                    icon = Icons.Rounded.Info,
                    summary = "Version ${BuildConfig.VERSION_NAME}",
                    expanded = expandedSection == SettingsSectionId.ABOUT,
                    onToggle = { expandedSection = it }
                ) {
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            AboutRow("Version", BuildConfig.VERSION_NAME)
                            AboutRow("Build", BuildConfig.VERSION_CODE.toString())
                            AboutRow("Commit", BuildConfig.GIT_COMMIT)
                            AboutRow("Built", formatBuildTime(BuildConfig.BUILD_TIME_MS))
                            AboutRow("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                            AboutRow("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        }
                    }
                }
            }

        }
    }
}

/**
 * One `label: value` line in the About card. Values are selectable because the reason to look at
 * this screen is to quote a build to someone -- a commit that cannot be copied out has to be
 * transcribed by eye.
 */
@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End
            )
        }
    }
}

/** The build's timestamp in the reader's own locale and time zone. */
private fun formatBuildTime(millis: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

/**
 * The collapsible groups on the Settings screen.
 *
 * Passed explicitly to [SettingsSection] rather than derived from the section's display title:
 * matching on the title meant renaming a heading silently re-pointed it at another section's
 * expand state, and every new section defaulted into whichever branch the `else` happened to name.
 */
private enum class SettingsSectionId { APPEARANCE, PLAYBACK, LYRICS, RECOGNITION, SCROBBLING, BACKUP, ABOUT }

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
 * Theme picker. Each card is a small live copy of that theme's backdrop scene, so a theme is
 * chosen by seeing it rather than by reading its name.
 */
@Composable
private fun ThemePaletteCard(
    selectedTheme: String,
    dynamicColorEnabled: Boolean,
    sceneMotion: Boolean,
    onSceneMotionChange: (Boolean) -> Unit,
    onSelectTheme: (String) -> Unit
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Theme", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (dynamicColorEnabled) {
                Text(
                    "Material You is on, so these themes aren't being applied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            AppTheme.entries.chunked(2).forEach { pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    pair.forEach { theme ->
                        ThemePreviewCard(
                            theme = theme,
                            selected = AppTheme.fromName(selectedTheme) == theme,
                            dark = dark,
                            animate = sceneMotion,
                            onClick = { onSelectTheme(theme.name) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
            SettingsToggleRow(
                title = "Moving backgrounds",
                subtitle = "Petals, snow, stars and rain drifting behind everything",
                checked = sceneMotion,
                onCheckedChange = onSceneMotionChange
            )
        }
    }
}

@Composable
private fun ThemePreviewCard(
    theme: AppTheme,
    selected: Boolean,
    dark: Boolean,
    animate: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    val accent = if (dark) theme.dark.accent else theme.light.accent
    val ring by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (selected) 3.dp else 1.dp,
        label = "themeRing"
    )
    Box(
        modifier = modifier
            .height(124.dp)
            .clip(shape)
            .border(ring, if (selected) accent else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f), shape)
            .clickable(onClickLabel = "Use ${theme.displayName}", onClick = onClick)
    ) {
        ThemeBackdrop(theme = theme, dark = dark, animate = animate)
        // A scrim along the bottom so the name reads over whatever is drifting behind it.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0.35f to androidx.compose.ui.graphics.Color.Transparent,
                        1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        ) {
            Text(
                theme.displayName,
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                theme.blurb,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(22.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(accent)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Current theme",
                    tint = if (dark) theme.dark.onAccent else theme.light.onAccent,
                    modifier = Modifier.size(15.dp)
                )
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

/**
 * Morphe-style lyrics service list: every source with a switch, tried top to bottom. Drag a row by
 * its handle to change the order; a row swaps with its neighbour once it has been dragged past half
 * of that neighbour's height.
 */
@Composable
private fun LyricsProviderList(
    settings: List<com.example.tgmusicai.data.lyrics.LyricsProviderSetting>,
    onChange: (List<com.example.tgmusicai.data.lyrics.LyricsProviderSetting>) -> Unit,
) {
    // Local copy while dragging, so the list follows the finger without a DataStore round trip
    // per swap; saved once when the drag ends.
    var rows by remember(settings) { mutableStateOf(settings) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val rowHeights = remember { mutableStateMapOf<com.example.tgmusicai.data.lyrics.LyricsProvider, Int>() }

    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = "Lyrics service",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                text = rows.filter { it.enabled }.joinToString(" > ") { it.provider.label }
                    .ifBlank { "All sources are off" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            rows.forEachIndexed { index, setting ->
              // Keyed by provider so a row's composable (and its in-progress drag gesture) moves
              // with it when the list reorders, instead of the gesture restarting at each swap.
              androidx.compose.runtime.key(setting.provider) {
                val isDragged = draggedIndex == index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                        .background(
                            if (isDragged) MaterialTheme.colorScheme.surfaceContainerHighest
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .onSizeChanged { rowHeights[setting.provider] = it.height }
                        .padding(start = 4.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragIndicator,
                        contentDescription = "Drag to reorder ${setting.provider.label}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(48.dp)
                            .padding(12.dp)
                            .pointerInput(setting.provider) {
                                detectDragGestures(
                                    onDragStart = { draggedIndex = rows.indexOfFirst { it.provider == setting.provider }; dragOffset = 0f },
                                    onDragEnd = { draggedIndex = null; dragOffset = 0f; onChange(rows) },
                                    onDragCancel = { draggedIndex = null; dragOffset = 0f; rows = settings },
                                ) { change, amount ->
                                    change.consume()
                                    val from = draggedIndex ?: return@detectDragGestures
                                    dragOffset += amount.y
                                    val down = from + 1
                                    val up = from - 1
                                    if (dragOffset > 0 && down < rows.size &&
                                        dragOffset > (rowHeights[rows[down].provider] ?: Int.MAX_VALUE) / 2f
                                    ) {
                                        val passed = rowHeights[rows[down].provider] ?: 0
                                        rows = rows.toMutableList().apply { add(down, removeAt(from)) }
                                        dragOffset -= passed
                                        draggedIndex = down
                                    } else if (dragOffset < 0 && up >= 0 &&
                                        -dragOffset > (rowHeights[rows[up].provider] ?: Int.MAX_VALUE) / 2f
                                    ) {
                                        val passed = rowHeights[rows[up].provider] ?: 0
                                        rows = rows.toMutableList().apply { add(up, removeAt(from)) }
                                        dragOffset += passed
                                        draggedIndex = up
                                    }
                                }
                            }
                    )
                    Text(
                        text = setting.provider.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (setting.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = setting.enabled,
                        onCheckedChange = { on ->
                            rows = rows.map { if (it.provider == setting.provider) it.copy(enabled = on) else it }
                            onChange(rows)
                        }
                    )
                }
              }
            }
        }
    }
}
