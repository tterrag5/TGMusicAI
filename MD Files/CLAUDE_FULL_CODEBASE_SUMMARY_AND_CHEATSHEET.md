# TGMusicAI Complete Codebase Blueprint & Master Function Cheat Sheet

This document serves as the **100% self-contained technical blueprint and master cheat sheet** for the TGMusicAI Android application. It contains all code specifications, build configurations, manifests, database schemas, SQL queries, network API payloads, design tokens, navigation routes, state flows, and an exhaustive function-by-function breakdown necessary to recreate the entire TGMusic app from scratch without viewing any original source code.

---

## Table of Contents
1. [Section 0: Complete Build & Dependency Specification](#section-0-complete-build--dependency-specification)
2. [Section 1: Android Manifest & Automotive Blueprint](#section-1-android-manifest--automotive-blueprint)
3. [Section 2: Room Database Complete Schema & Migration Specification](#section-2-room-database-complete-schema--migration-specification)
4. [Section 3: Complete Network API Payloads & Endpoints](#section-3-complete-network-api-payloads--endpoints)
5. [Section 4: Complete Jetpack Compose UI & Soft Theme Blueprint](#section-4-complete-jetpack-compose-ui--soft-theme-blueprint)
6. [Section 5: Exhaustive Class & Function Cheat Sheet](#section-5-exhaustive-class--function-cheat-sheet)

---

# Section 0: Complete Build & Dependency Specification

TGMusicAI is built on Kotlin 2.2 with Jetpack Compose, Room DB, Media3 ExoPlayer, and KSP code generation. The build environment can be reproducibly declared using Nix Flakes.

### 0.1 Nix Environment Specification (`flake.nix`)
```nix
{
  description = "TGMusicAI Android Development Environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "34.0.0" "35.0.0" ];
          platformVersions = [ "34" "35" ];
          abiVersions = [ "x86_64" "arm64-v8a" ];
          includeEmulator = false;
          includeSystemImages = false;
          includeNDK = false;
        };

        androidSdk = androidComposition.androidsdk;
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            pkgs.jdk17
            pkgs.android-tools
            pkgs.gradle
            androidSdk
          ];

          shellHook = ''
            export JAVA_HOME="${pkgs.jdk17.home}"
            export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export PATH="$ANDROID_HOME/tools:$ANDROID_HOME/tools/bin:$ANDROID_HOME/platform-tools:$PATH"
            unset ANDROID_PREFS_ROOT
          '';
        };
      }
    );
}
```

### 0.2 Gradle Version Catalog (`gradle/libs.versions.toml`)
```toml
[versions]
agp = "9.3.2"
coreKtx = "1.10.1"
junit = "4.13.2"
junitVersion = "1.1.5"
espressoCore = "3.5.1"
lifecycleRuntimeKtx = "2.8.7"
lifecycleViewmodelCompose = "2.8.7"
lifecycleRuntimeCompose = "2.8.7"
activityCompose = "1.10.1"
kotlin = "2.2.10"
composeBom = "2024.09.00"
googleDevtoolsKsp = "2.3.5"
jetbrainsKotlinPluginSerialization = "2.2.21"
kotlinxSerializationCore = "1.9.0"
navigation3Ui = "1.0.1"
navigation3Runtime = "1.0.1"
adaptive = "1.3.+"
adaptiveLayout = "1.3.+"
adaptiveNavigation3 = "1.3.+"
lifecycleViewmodelNavigation3 = "2.11.+"
roomRuntime = "2.7.0"
roomKtx = "2.7.0"
roomCompiler = "2.7.0"
kotlinxCoroutinesTest = "1.10.2"
core = "1.6.1"
runner = "1.6.2"
coilCompose = "2.7.0"
retrofit = "2.12.0"
converterMoshi = "2.12.0"
kotlinxCoroutinesAndroid = "1.10.2"
kotlinxCoroutinesCore = "1.10.2"
kotlinxCoroutinesGuava = "1.10.2"
accompanistPermissions = "0.37.3"
playServicesLocation = "21.3.0"
playServicesAuth = "22.0.0"
cameraCamera2 = "1.5.0"
cameraLifecycle = "1.5.0"
cameraView = "1.5.0"
cameraCore = "1.5.0"
loggingInterceptor = "4.10.0"
okhttp = "4.10.0"
moshiKotlin = "1.15.2"
moshiKotlinCodegen = "1.15.2"
datastorePreferences = "1.1.7"
material = "1.4.+"
media3 = "1.11.1"
newpipeExtractor = "0.24.8"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleViewmodelCompose" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeCompose" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
kotlinx-serialization-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-core", version.ref = "kotlinxSerializationCore" }
androidx-navigation3-ui = { group = "androidx.navigation3", name = "navigation3-ui", version.ref = "navigation3Ui" }
androidx-navigation3-runtime = { group = "androidx.navigation3", name = "navigation3-runtime", version.ref = "navigation3Runtime" }
androidx-compose-adaptive = { group = "androidx.compose.material3.adaptive", name = "adaptive", version.ref = "adaptive" }
androidx-compose-adaptive-layout = { group = "androidx.compose.material3.adaptive", name = "adaptive-layout", version.ref = "adaptiveLayout" }
androidx-compose-adaptive-navigation3 = { group = "androidx.compose.material3.adaptive", name = "adaptive-navigation3", version.ref = "adaptiveNavigation3" }
androidx-lifecycle-viewmodel-navigation3 = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-navigation3", version.ref = "lifecycleViewmodelNavigation3" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "roomRuntime" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "roomKtx" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "roomCompiler" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutinesTest" }
androidx-core = { group = "androidx.test", name = "core", version.ref = "core" }
androidx-runner = { group = "androidx.test", name = "runner", version.ref = "runner" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons-core = { group = "androidx.compose.material", name = "material-icons-core" }
androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coilCompose" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
converter-moshi = { group = "com.squareup.retrofit2", name = "converter-moshi", version.ref = "converterMoshi" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutinesAndroid" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "kotlinxCoroutinesCore" }
kotlinx-coroutines-guava = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-guava", version.ref = "kotlinxCoroutinesGuava" }
accompanist-permissions = { group = "com.google.accompanist", name = "accompanist-permissions", version.ref = "accompanistPermissions" }
play-services-location = { group = "com.google.android.gms", name = "play-services-location", version.ref = "playServicesLocation" }
play-services-auth = { group = "com.google.android.gms", name = "play-services-auth", version.ref = "playServicesAuth" }
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "cameraCamera2" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "cameraLifecycle" }
androidx-camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "cameraView" }
androidx-camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "cameraCore" }
logging-interceptor = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "loggingInterceptor" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
moshi-kotlin = { group = "com.squareup.moshi", name = "moshi-kotlin", version.ref = "moshiKotlin" }
moshi-kotlin-codegen = { group = "com.squareup.moshi", name = "moshi-kotlin-codegen", version.ref = "moshiKotlinCodegen" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastorePreferences" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
androidx-media3-session = { group = "androidx.media3", name = "media3-session", version.ref = "media3" }
androidx-media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }
newpipe-extractor = { group = "com.github.TeamNewPipe.NewPipeExtractor", name = "extractor", version = "v0.24.8" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
google-devtools-ksp = { id = "com.google.devtools.ksp", version.ref = "googleDevtoolsKsp" }
jetbrains-kotlin-plugin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "jetbrainsKotlinPluginSerialization" }
```

### 0.3 Application Build Script (`app/build.gradle.kts`)
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
}

android {
    namespace = "com.example.tgmusicai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.tgmusicai"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization { enable = false }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.newpipe.extractor)
    implementation(libs.coil.compose)
    implementation(libs.converter.moshi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.logging.interceptor)
    implementation(libs.material)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.play.services.location)
    implementation(libs.play.services.auth)
    implementation(libs.retrofit)

    "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
}
```

---

# Section 1: Android Manifest & Automotive Blueprint

### 1.1 Complete Manifest Declaration (`AndroidManifest.xml`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Permissions required for audio playback, storage, alarm scheduling, and foreground service -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.DISABLE_KEYGUARD" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.TGMusicAI">

        <!-- Android Auto Application Metadata -->
        <meta-data
            android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />

        <!-- Main Launcher Activity -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:launchMode="singleTop"
            android:theme="@style/Theme.TGMusicAI"
            android:windowSoftInputMode="adjustResize"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Alarm Lock Screen Activity -->
        <activity
            android:name=".alarm.AlarmActivity"
            android:exported="false"
            android:showWhenLocked="true"
            android:turnScreenOn="true"
            android:theme="@style/Theme.TGMusicAI" />

        <!-- System Alarm Trigger Receiver -->
        <receiver
            android:name=".alarm.AlarmReceiver"
            android:exported="false" />

        <!-- Reboot Alarm Rescheduler Receiver -->
        <receiver
            android:name=".alarm.BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

        <!-- Media3 Background Service -->
        <service
            android:name=".playback.PlaybackService"
            android:foregroundServiceType="mediaPlayback"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaLibraryService" />
                <action android:name="android.media.browse.MediaBrowserService" />
            </intent-filter>
        </service>
    </application>

</manifest>
```

### 1.2 Android Automotive Descriptor (`res/xml/automotive_app_desc.xml`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
```

---

# Section 2: Room Database Complete Schema & Migration Specification

The local database is named `tgmusicai_database` and uses Room version 8.

### 2.1 Complete Entity Data Classes

#### 1. `Song` Entity (`tableName = "songs"`)
```kotlin
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mediaUri: String,
    @ColumnInfo(name = "producer") val producer: String? = null,
    @ColumnInfo(name = "lyrics") val lyrics: String? = null,
    @ColumnInfo(name = "artworkUri") val artworkUri: String? = null,
    @ColumnInfo(name = "youtube_id") val youtubeId: String? = null,
    @ColumnInfo(name = "is_downloaded") val isDownloaded: Boolean = true,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false
)
```

#### 2. `Playlist` Entity (`tableName = "playlists"`)
```kotlin
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val playlistId: Long = 0,
    val name: String,
    val description: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "is_smart") val isSmart: Boolean = false,
    @ColumnInfo(name = "youtube_playlist_id") val youtubePlaylistId: String? = null,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null
)
```

#### 3. `PlaylistSongCrossRef` Entity (`tableName = "playlist_song_cross_ref"`)
```kotlin
@Entity(
    tableName = "playlist_song_cross_ref",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index(value = ["songId"])]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val position: Int = 0
)
```

#### 4. `SongStats` Entity (`tableName = "song_stats"`)
```kotlin
@Entity(tableName = "song_stats")
data class SongStats(
    @PrimaryKey val songId: Long,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null
)
```

#### 5. `Alarm` Entity (`tableName = "alarms"`)
```kotlin
enum class AlarmToneType { SONG, PLAYLIST, RANDOM_LIKED }

@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeInMillis: Long,
    val isEnabled: Boolean = true,
    val repeatDays: String = "",
    val toneType: AlarmToneType = AlarmToneType.RANDOM_LIKED,
    val toneUriOrId: String = "",
    val snoozeMinutes: Int = 10,
    val label: String = "Alarm"
)
```

#### 6. `PendingDownload` Entity (`tableName = "pending_downloads"`)
```kotlin
@Entity(tableName = "pending_downloads")
data class PendingDownload(
    @PrimaryKey val videoId: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val queuedAt: Long = System.currentTimeMillis()
)
```

#### 7. Relations (`PlaylistWithSongs` & `SongWithStats`)
```kotlin
data class PlaylistWithSongs(
    @Embedded val playlist: Playlist,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "id",
        associateBy = Junction(PlaylistSongCrossRef::class, parentColumn = "playlistId", entityColumn = "songId")
    )
    val songs: List<Song>
)

data class SongWithStats(
    val song: Song,
    val stats: SongStats
)
```

### 2.2 Complete Room DAO Interfaces & SQL Queries

#### `SongDao`
- `@Insert(onConflict = IGNORE) suspend fun insertSongs(songs: List<Song>)`
- `@Insert(onConflict = REPLACE) suspend fun insertSong(song: Song): Long`
- `@Query("SELECT * FROM songs ORDER BY title ASC") fun getAllSongs(): Flow<List<Song>>`
- `@Query("SELECT * FROM songs ORDER BY title ASC") suspend fun getAllSongsList(): List<Song>`
- `@Query("SELECT * FROM songs WHERE is_downloaded = 1 ORDER BY title ASC") fun getDownloadedSongs(): Flow<List<Song>>`
- `@Query("SELECT * FROM songs WHERE is_downloaded = 0 ORDER BY title ASC") fun getNotDownloadedSongs(): Flow<List<Song>>`
- `@Query("SELECT * FROM songs WHERE is_downloaded = 1 ORDER BY RANDOM() LIMIT :limit") suspend fun getRandomDownloadedSongs(limit: Int): List<Song>`
- `@Query("SELECT * FROM songs WHERE is_pinned = 1 ORDER BY title ASC") fun getPinnedSongs(): Flow<List<Song>>`
- `@Query("SELECT * FROM songs WHERE artworkUri IS NULL OR artworkUri = ''") suspend fun getSongsMissingArtwork(): List<Song>`
- `@Query("SELECT * FROM songs WHERE id = :songId") suspend fun getSongById(songId: Long): Song?`
- `@Query("SELECT * FROM songs WHERE mediaUri = :uri LIMIT 1") suspend fun getSongByUri(uri: String): Song?`
- `@Query("SELECT * FROM songs WHERE youtube_id = :youtubeId LIMIT 1") suspend fun getSongByYoutubeId(youtubeId: String): Song?`
- `@Query("SELECT * FROM songs WHERE LOWER(title) = LOWER(:title) AND LOWER(artist) = LOWER(:artist) LIMIT 1") suspend fun getSongByTitleAndArtist(title: String, artist: String): Song?`
- `@Query("SELECT * FROM songs WHERE LOWER(title) = LOWER(:title)") suspend fun getSongsByTitle(title: String): List<Song>`
- `@Query("SELECT * FROM songs ORDER BY id DESC LIMIT :limit") fun getRecentlyAddedSongs(limit: Int = 50): Flow<List<Song>>`
- `@Query("SELECT * FROM songs WHERE id NOT IN (SELECT songId FROM song_stats WHERE playCount > 0) ORDER BY title ASC") fun getUnplayedSongs(): Flow<List<Song>>`
- `@Delete suspend fun deleteSong(song: Song)`
- `@Query("UPDATE songs SET lyrics = :lyrics WHERE id = :id") suspend fun updateSongLyrics(id: Long, lyrics: String?)`
- `@Query("UPDATE songs SET artworkUri = :artworkUri WHERE id = :id") suspend fun updateSongArtwork(id: Long, artworkUri: String?)`
- `@Query("UPDATE songs SET is_pinned = :isPinned WHERE id = :id") suspend fun updatePinStatus(id: Long, isPinned: Boolean)`
- `@Query("UPDATE songs SET is_downloaded = :isDownloaded, mediaUri = :mediaUri WHERE id = :id") suspend fun updateDownloadStatus(id: Long, isDownloaded: Boolean, mediaUri: String)`
- `@Query("UPDATE songs SET artist = :artist WHERE id = :id") suspend fun updateSongArtist(id: Long, artist: String)`

#### `PlaylistDao`
- `@Insert(onConflict = REPLACE) suspend fun insertPlaylist(playlist: Playlist): Long`
- `@Insert(onConflict = REPLACE) suspend fun insertPlaylistSongCrossRef(crossRef: PlaylistSongCrossRef)`
- `@Query("SELECT * FROM playlists ORDER BY createdAt DESC") fun getAllPlaylists(): Flow<List<Playlist>>`
- `@Query("SELECT * FROM playlists ORDER BY createdAt DESC") suspend fun getAllPlaylistsList(): List<Playlist>`
- `@Query("SELECT * FROM playlists WHERE is_pinned = 1 ORDER BY createdAt DESC") fun getPinnedPlaylists(): Flow<List<Playlist>>`
- `@Query("SELECT * FROM playlists WHERE is_smart = 1 ORDER BY createdAt DESC") fun getSmartPlaylists(): Flow<List<Playlist>>`
- `@Query("SELECT * FROM playlists WHERE name = :name LIMIT 1") suspend fun getPlaylistByName(name: String): Playlist?`
- `@Query("SELECT * FROM playlists WHERE name = :name") suspend fun getPlaylistsByName(name: String): List<Playlist>`
- `@Query("SELECT * FROM playlists WHERE playlistId = :playlistId") suspend fun getPlaylistById(playlistId: Long): Playlist?`
- `@Transaction @Query("SELECT * FROM playlists WHERE playlistId = :playlistId") fun getPlaylistWithSongs(playlistId: Long): Flow<PlaylistWithSongs?>`
- `@Transaction @Query("SELECT * FROM playlists WHERE playlistId = :playlistId") suspend fun getPlaylistWithSongsSync(playlistId: Long): PlaylistWithSongs?`
- `@Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId") suspend fun deleteCrossRefsForPlaylist(playlistId: Long)`
- `@Delete suspend fun deletePlaylistEntity(playlist: Playlist)`
- `@Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId") suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)`
- `@Query("UPDATE playlists SET is_pinned = :isPinned WHERE playlistId = :playlistId") suspend fun updatePinStatus(playlistId: Long, isPinned: Boolean)`
- `@Query("UPDATE playlists SET description = :description WHERE playlistId = :playlistId") suspend fun updatePlaylistDescription(playlistId: Long, description: String?)`
- `@Query("SELECT EXISTS(SELECT 1 FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId)") fun isSongInPlaylistFlow(playlistId: Long, songId: Long): Flow<Boolean>`
- `@Query("SELECT EXISTS(SELECT 1 FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId)") suspend fun isSongInPlaylistSync(playlistId: Long, songId: Long): Boolean`
- `@Query("SELECT * FROM playlists WHERE youtube_playlist_id = :youtubePlaylistId LIMIT 1") suspend fun getPlaylistByYoutubeId(youtubePlaylistId: String): Playlist?`
- `@Query("SELECT * FROM playlists WHERE youtube_playlist_id IS NOT NULL ORDER BY createdAt DESC") fun getYoutubeSyncedPlaylists(): Flow<List<Playlist>>`
- `@Query("SELECT * FROM playlists WHERE youtube_playlist_id IS NOT NULL") suspend fun getYoutubeSyncedPlaylistsList(): List<Playlist>`
- `@Query("UPDATE playlists SET last_synced_at = :timestamp WHERE playlistId = :playlistId") suspend fun updateLastSyncedAt(playlistId: Long, timestamp: Long)`
- `@Query("SELECT songId FROM playlist_song_cross_ref WHERE playlistId = :playlistId") suspend fun getSongIdsInPlaylist(playlistId: Long): List<Long>`
- `@Query("SELECT playlistId FROM playlist_song_cross_ref WHERE songId = :songId") suspend fun getPlaylistIdsForSong(songId: Long): List<Long>`
- `@Query("DELETE FROM playlist_song_cross_ref WHERE songId = :songId") suspend fun deleteCrossRefsForSong(songId: Long)`

#### `SongStatsDao`
- `@Insert(onConflict = REPLACE) suspend fun insertOrUpdate(stats: SongStats)`
- `@Query("SELECT * FROM song_stats WHERE songId = :songId") suspend fun getStatsForSong(songId: Long): SongStats?`
- `@Query("SELECT * FROM song_stats ORDER BY playCount DESC LIMIT :limit") fun getMostPlayedStats(limit: Int): Flow<List<SongStats>>`
- `@Query("SELECT * FROM song_stats WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit") fun getRecentlyPlayedStats(limit: Int): Flow<List<SongStats>>`
- `@Query("SELECT * FROM song_stats ORDER BY playCount DESC LIMIT :limit") suspend fun getMostPlayedStatsSync(limit: Int): List<SongStats>`
- `@Query("DELETE FROM song_stats WHERE songId = :songId") suspend fun deleteStatsForSong(songId: Long)`
- `@Query("DELETE FROM song_stats WHERE songId NOT IN (SELECT id FROM songs)") suspend fun deleteOrphanedStats()`

#### `AlarmDao`
- `@Query("SELECT * FROM alarms ORDER BY id ASC") fun getAllAlarms(): Flow<List<Alarm>>`
- `@Query("SELECT * FROM alarms ORDER BY id ASC") suspend fun getAllAlarmsList(): List<Alarm>`
- `@Query("SELECT * FROM alarms WHERE isEnabled = 1") suspend fun getEnabledAlarms(): List<Alarm>`
- `@Query("SELECT * FROM alarms WHERE id = :alarmId") suspend fun getAlarmById(alarmId: Long): Alarm?`
- `@Insert(onConflict = REPLACE) suspend fun insertAlarm(alarm: Alarm): Long`
- `@Update suspend fun updateAlarm(alarm: Alarm)`
- `@Delete suspend fun deleteAlarm(alarm: Alarm)`
- `@Query("DELETE FROM alarms WHERE id = :alarmId") suspend fun deleteAlarmById(alarmId: Long)`

#### `PendingDownloadDao`
- `@Insert(onConflict = REPLACE) suspend fun insert(pendingDownload: PendingDownload)`
- `@Query("DELETE FROM pending_downloads WHERE videoId = :videoId") suspend fun deleteByVideoId(videoId: String)`
- `@Query("SELECT * FROM pending_downloads ORDER BY queuedAt ASC") suspend fun getAll(): List<PendingDownload>`

### 2.3 Migration Strategy (v1 to v8)

```kotlin
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists ADD COLUMN youtube_playlist_id TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE playlists ADD COLUMN last_synced_at INTEGER DEFAULT NULL")
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_downloads (
                videoId TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                uploader TEXT NOT NULL,
                durationSeconds INTEGER NOT NULL,
                queuedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
```

The database builder configures `.addMigrations(MIGRATION_6_7, MIGRATION_7_8).fallbackToDestructiveMigration()` so schema upgrades preserve all user music records while falling back to destructive recreation for unhandled legacy version gaps.

---

# Section 3: Complete Network API Payloads & Endpoints

TGMusicAI integrates with YouTube InnerTube, Cobalt, Piped, Invidious, LrcLib, iTunes Search, and MusicBrainz APIs.

### 3.1 YouTube InnerTube API Payloads
- **Player Endpoint**: `POST https://www.youtube.com/youtubei/v1/player`
- **Search Endpoint**: `POST https://www.youtube.com/youtubei/v1/search`

#### Request JSON for Player (`ANDROID` client context):
```json
{
  "videoId": "VIDEO_ID_HERE",
  "context": {
    "client": {
      "clientName": "ANDROID",
      "clientVersion": "19.29.37",
      "androidSdkVersion": 34,
      "hl": "en",
      "gl": "US"
    }
  }
}
```

#### Request JSON for Search (`ANDROID_MUSIC` client context):
```json
{
  "query": "search terms artist song",
  "context": {
    "client": {
      "clientName": "ANDROID_MUSIC",
      "clientVersion": "7.02.52",
      "androidSdkVersion": 34,
      "hl": "en",
      "gl": "US"
    }
  }
}
```

### 3.2 Cobalt API Payloads
- **Endpoint**: `POST https://api.cobalt.tools/api/json`
- **Headers**: `Content-Type: application/json`, `Accept: application/json`

#### Request JSON:
```json
{
  "url": "https://www.youtube.com/watch?v=VIDEO_ID_HERE",
  "downloadMode": "audio",
  "audioFormat": "m4a",
  "audioBitrate": "320"
}
```

#### Response JSON:
```json
{
  "status": "tunnel",
  "url": "https://download.stream.cobalt.tools/direct_audio_stream_url",
  "filename": "track.m4a"
}
```

### 3.3 Piped & Invidious REST API Endpoints
- **Piped Endpoint**: `https://pipedapi.wireway.ch` (and live directory at `https://piped-instances.kavin.rocks`)
  - **Stream URL**: `GET /streams/{videoId}`
  - **Search URL**: `GET /search?q={query}&filter=music_songs`
  - **Captions URL**: `GET /captions/{videoId}`
  - **Stream Response JSON Structure**:
    ```json
    {
      "title": "Track Title",
      "uploader": "Artist Name",
      "duration": 210,
      "audioStreams": [
        {
          "url": "https://video.pipedproxy.ch/videoplayback?...",
          "proxyUrl": "https://pipedapi.wireway.ch/proxy?url=...",
          "format": "M4A",
          "mimeType": "audio/mp4",
          "bitrate": 128000
        }
      ]
    }
    ```

- **Invidious Endpoint**: `https://invidious.nerdvpn.de`, `https://inv.nadeko.net`, `https://invidious.tiekoetter.com` (and live directory at `https://api.invidious.io/instances.json`)
  - **Video Info URL**: `GET /api/v1/videos/{videoId}`
  - **Format Response JSON Structure**:
    ```json
    {
      "title": "Track Title",
      "author": "Artist Name",
      "lengthSeconds": 210,
      "adaptiveFormats": [
        {
          "url": "https://invidious.nerdvpn.de/videoplayback?...",
          "type": "audio/webm; codecs=\"opus\"",
          "container": "webm",
          "encoding": "opus",
          "bitrate": "160000"
        }
      ]
    }
    ```

### 3.4 LrcLib API Format
- **Direct Get URL**: `GET https://lrclib.net/api/get?track_name={encoded_title}&artist_name={encoded_artist}&duration={duration_sec}`
- **Search Query URL**: `GET https://lrclib.net/api/search?q={encoded_query}`
- **User-Agent Header**: `TGMusicAI/1.0 (https://github.com/tterrag5/TGMusicAI)`
- **Response JSON Format**:
  ```json
  {
    "id": 987654,
    "trackName": "Title",
    "artistName": "Artist",
    "albumName": "Album",
    "duration": 210,
    "plainLyrics": "Line 1\nLine 2",
    "syncedLyrics": "[00:12.34] Line 1\n[00:16.50] Line 2"
  }
  ```

### 3.5 iTunes Search & High-Res Cover Transformation
- **Search URL**: `GET https://itunes.apple.com/search?entity=song&limit=5&term={encoded_artist_and_title}`
- **High-Res Transformation String Rule**:
  ```kotlin
  fun getHighResItunesUrl(rawUrl: String): String {
      if (rawUrl.isBlank()) return rawUrl
      return rawUrl.replace("100x100bb.jpg", "1000x1000bb.jpg")
          .replace("100x100bb.png", "1000x1000bb.png")
          .replace("100x100", "1000x1000")
  }
  ```

---

# Section 4: Complete Jetpack Compose UI & Soft Theme Blueprint

### 4.1 Theme Palette Hex Colors

| Soft Theme | Background Hex | Card/Surface Hex | Primary Accent Hex |
| --- | --- | --- | --- |
| **`YT_DARK`** | `#0F0F0F` | `#1F1F1F` | `#E53935` (Soft Red) |
| **`PASTEL_MIDNIGHT`** | `#121824` | `#1B2436` | `#4FD1C5` (Soft Teal) |
| **`WARM_AMBER`** | `#1C1917` | `#292524` | `#F59E0B` (Warm Amber) |
| **`NORDIC_SLATE`** | `#1E222A` | `#282C34` | `#61AFEF` (Soft Cyan) |

#### Material 3 `DarkColorScheme` Implementation Pattern
```kotlin
enum class AppTheme(val displayName: String) {
    YT_DARK("YT Dark"),
    PASTEL_MIDNIGHT("Pastel Midnight"),
    WARM_AMBER("Warm Amber"),
    NORDIC_SLATE("Nordic Slate")
}

fun getSoftColorScheme(themeName: String): ColorScheme {
    return when (AppTheme.fromName(themeName)) {
        AppTheme.YT_DARK -> darkColorScheme(
            primary = Color(0xFFE53935),
            background = Color(0xFF0F0F0F),
            surface = Color(0xFF0F0F0F),
            surfaceVariant = Color(0xFF1F1F1F)
        )
        AppTheme.PASTEL_MIDNIGHT -> darkColorScheme(
            primary = Color(0xFF4FD1C5),
            background = Color(0xFF121824),
            surface = Color(0xFF121824),
            surfaceVariant = Color(0xFF1B2436)
        )
        AppTheme.WARM_AMBER -> darkColorScheme(
            primary = Color(0xFFF59E0B),
            background = Color(0xFF1C1917),
            surface = Color(0xFF1C1917),
            surfaceVariant = Color(0xFF292524)
        )
        AppTheme.NORDIC_SLATE -> darkColorScheme(
            primary = Color(0xFF61AFEF),
            background = Color(0xFF1E222A),
            surface = Color(0xFF1E222A),
            surfaceVariant = Color(0xFF282C34)
        )
    }
}
```

### 4.2 Type-Safe Navigation 3 Routes (`NavRoutes.kt`)
All routes implement `NavKey` and are annotated with `@Serializable`:
- `Screen.Home`: Primary Speed Dial Home tab.
- `Screen.Library`: Local song library list/grid view.
- `Screen.Playlists`: Playlist grid & creation tab.
- `Screen.YouTube`: Cloud search & streaming tab.
- `Screen.Alarms`: Musical alarms schedule & management tab.
- `Screen.Stats`: Listening statistics & storage breakdown tab.
- `Screen.PlaylistDetail(val playlistId: Long, val playlistName: String)`: Playlist content detail view.
- `Screen.NowPlayingFull`: Expanded full-screen player view.
- `Screen.GoogleSync`: Google account playlist import & sync screen.
- `Screen.Downloads`: Background cloud downloads progress monitor.

### 4.3 Interactive ViewModels & Reactive StateFlows

1. **`HomeViewModel`**:
   - `downloadedOnlyFilter: StateFlow<Boolean>`
   - `pinnedSongs: StateFlow<List<Song>>`
   - `pinnedPlaylists: StateFlow<List<Playlist>>`
   - `listenAgainSongs: StateFlow<List<SongWithStats>>`
   - `mostPlayedSongs: StateFlow<List<SongWithStats>>`
   - `backupStatusMessage: StateFlow<String?>`
   - Actions: `setDownloadedOnly()`, `togglePinSong()`, `togglePinPlaylist()`, `exportBackup()`, `importBackup()`

2. **`LibraryViewModel`**:
   - `searchQuery: StateFlow<String>`
   - `isGridView: StateFlow<Boolean>`
   - `selectedSongIds: StateFlow<Set<Long>>`
   - `filteredSongs: StateFlow<List<Song>>`
   - Actions: `onSearchQueryChanged()`, `toggleGridView()`, `startSelection()`, `toggleSongSelected()`, `deleteSelectedSongs()`, `addSelectedSongsToPlaylist()`

3. **`PlayerViewModel`**:
   - `currentSong: StateFlow<Song?>`
   - `isPlaying: StateFlow<Boolean>`
   - `currentPositionMs: StateFlow<Long>`
   - `durationMs: StateFlow<Long>`
   - `repeatMode: StateFlow<Int>` (0=Off, 1=Loop Queue, 2=Loop Track)
   - `isShuffleEnabled: StateFlow<Boolean>`
   - `isLiked: StateFlow<Boolean>`
   - `sleepTimerRemainingMinutes: StateFlow<Int?>`
   - `parsedLyrics: StateFlow<List<LyricLine>>`
   - Actions: `playSong()`, `playQueue()`, `togglePlayPause()`, `seekTo()`, `skipToNext()`, `skipToPrevious()`, `toggleLikeCurrentSong()`, `startSleepTimer()`, `fetchLyrics()`

4. **`YouTubeViewModel`**:
   - `searchQuery: StateFlow<String>`
   - `searchResults: StateFlow<List<YouTubeSearchResult>>`
   - `isLoading: StateFlow<Boolean>`
   - `downloadProgressMap: StateFlow<Map<String, DownloadProgressState>>`
   - Actions: `onSearchQueryChanged()`, `performSearch()`, `playTrack()`, `downloadTrack()`, `addCloudTrackToPlaylist()`

---

# Section 5: Exhaustive Class & Function Cheat Sheet

## Stage 1: Data & Storage Layer

### 1. Song.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/entity/Song.kt`
- `data class Song(...)`: Data class representing the `songs` table schema with fields `id`, `title`, `artist`, `album`, `durationMs`, `mediaUri`, `producer`, `lyrics`, `artworkUri`, `youtubeId`, `isDownloaded`, `isPinned`.

### 2. Playlist.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/entity/Playlist.kt`
- `data class Playlist(...)`: Data class representing `playlists` table schema with fields `playlistId`, `name`, `description`, `createdAt`, `isPinned`, `isSmart`, `youtubePlaylistId`, `lastSyncedAt`.

### 3. PlaylistSongCrossRef.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/entity/PlaylistSongCrossRef.kt`
- `data class PlaylistSongCrossRef(...)`: Junction entity linking `Playlist` and `Song` with composite PK `(playlistId, songId)` and `position`.

### 4. SongStats.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/entity/SongStats.kt`
- `data class SongStats(...)`: Tracks playback metrics with PK `songId`, `playCount`, and `lastPlayedAt`.

### 5. Alarm.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/entity/Alarm.kt`
- `enum class AlarmToneType`: Enum with values `SONG`, `PLAYLIST`, `RANDOM_LIKED`.
- `data class Alarm(...)`: Scheduled alarm entity with fields `id`, `timeInMillis`, `isEnabled`, `repeatDays`, `toneType`, `toneUriOrId`, `snoozeMinutes`, `label`.

### 6. PendingDownload.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/entity/PendingDownload.kt`
- `data class PendingDownload(...)`: Tracks incomplete downloads with PK `videoId`, `title`, `uploader`, `durationSeconds`, `queuedAt`.

### 7. SongDao.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/dao/SongDao.kt`
- `insertSongs(songs: List<Song>)`: Mass inserts songs, ignoring on conflict.
- `insertSong(song: Song): Long`: Inserts/replaces a single song.
- `getAllSongs(): Flow<List<Song>>`: Reactive stream of all songs sorted by title.
- `getDownloadedSongs(): Flow<List<Song>>`: Reactive stream of local/downloaded songs.
- `getRandomDownloadedSongs(limit: Int): List<Song>``: Returns random downloaded songs for autoplay queue extension.
- `getPinnedSongs(): Flow<List<Song>>`: Reactive stream of pinned songs for Speed Dial.
- `findByTitleAndNormalizedArtist(title: String, artist: String): Song?`: Normalized artist matching transaction across title candidates.
- `updateSongLyrics(id: Long, lyrics: String?)`: Updates lyrics column in Room.
- `updateSongArtwork(id: Long, artworkUri: String?)`: Updates artwork URI column in Room.

### 8. PlaylistDao.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/dao/PlaylistDao.kt`
- `insertPlaylist(playlist: Playlist): Long`: Inserts or updates playlist.
- `getPlaylistWithSongs(playlistId: Long): Flow<PlaylistWithSongs?>`: Transactional relation join query.
- `deletePlaylist(playlist: Playlist)`: Transaction removing cross-refs and playlist entity.
- `removeSongFromPlaylist(playlistId: Long, songId: Long)`: Deletes specific cross-ref link.
- `isSongInPlaylistFlow(playlistId: Long, songId: Long): Flow<Boolean>``: Reactive boolean check for Liked Music state.

### 9. SongStatsDao.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/dao/SongStatsDao.kt`
- `incrementPlayCount(songId: Long, currentTime: Long)`: Transactional play count increment.
- `deleteOrphanedStats()`: Deletes stat rows with no matching `Song.id`.

### 10. AlarmDao.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/dao/AlarmDao.kt`
- `getAllAlarms(): Flow<List<Alarm>>`: Flow of all alarms.
- `getEnabledAlarms(): List<Alarm>`: Synchronous query for active boot scheduling.

### 11. AppDatabase.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/AppDatabase.kt`
- `getDatabase(context: Context): AppDatabase`: Singleton DB factory applying `MIGRATION_6_7`, `MIGRATION_7_8`, and fallback strategies.

### 12. MusicRepository.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/repository/MusicRepository.kt`
- `ensureSmartPlaylistsExist()`: Mutex-guarded smart playlist creator.
- `getOrCreateLikedMusicPlaylistId(): Long`: Fetches or creates Liked Music smart playlist.
- `toggleLikeSong(songId: Long): Boolean`: Toggles song membership in Liked Music playlist.
- `deleteSongsCompletely(songIds: List<Long>): Int`: Deletes local audio files, cross-refs, stats, and DB records.
- `deduplicateLibrary(): Int`: One-time library deduplication pass merging duplicate tracks.

### 13. CoverArtScraper.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/repository/CoverArtScraper.kt`
- `getHighResItunesUrl(rawUrl: String): String`: Transforms 100x100 iTunes thumbnails to 1000x1000.
- `scrapeAndSaveArtwork(song: Song): String?`: Executes multi-source scrape (iTunes -> MusicBrainz -> YouTube) and downloads image to `Covers/{songId}.jpg`.

### 14. LyricsRepository.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/repository/LyricsRepository.kt`
- `parseLyrics(rawLyrics: String?): List<LyricLine>`: Parses LRC timestamped or plain lyrics strings into sorted `LyricLine` lists.
- `fetchAndSaveLyrics(song: Song, forceFetch: Boolean): String?`: Multi-source lyrics fetch (LrcLib -> Embedded ID3 -> YouTube captions) and DB caching.

### 15. BackupManager.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/BackupManager.kt`
- `exportBackup(context: Context): File`: Serializes DB tables into `manifest.json` and zips with local audio files into `.tgmusic` archive.
- `importBackup(context: Context, inputStream: InputStream): Boolean`: Zip Slip protected archive extractor restoring DB tables and audio files.

---

## Stage 2: YouTube Cloud Engine

### 16. YouTubeExtractor.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/youtube/YouTubeExtractor.kt`
- `search(query: String): List<YouTubeSearchResult>`: Queries NewPipeExtractor, falling back to Piped/Invidious endpoints.
- `extractAudioStream(videoId: String): YouTubeAudioStream?`: Fast pre-flight verified stream extractor for direct playback.
- `parsePipedProxyAudioStreams(jsonArray: JSONArray, baseUrl: String)`: Parses Piped API audio stream JSON with proxy fallbacks.
- `parseInvidiousAdaptiveFormats(jsonArray: JSONArray)`: Parses Invidious adaptive formats JSON.
- `fetchLiveInvidiousInstances()`: Fetches self-healing live instance list from `api.invidious.io`.

### 17. CloudDownloadManager.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/youtube/CloudDownloadManager.kt`
- `downloadTrack(videoId: String, title: String, uploader: String, durationSeconds: Long)`: Enqueues bounded parallel download with Semaphore limit 3.
- `resumePendingDownloads()`: Auto-resumes interrupted downloads from `pending_downloads` DAO on app startup.

---

## Stage 3: Playback & Alarm Subsystems

### 18. PlaybackService.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/playback/PlaybackService.kt`
- Android `MediaLibraryService` running ExoPlayer in foreground service with media playback notification and Android Auto tree browsing callbacks.

### 19. MediaControllerManager.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/playback/MediaControllerManager.kt`
- Controls playback via `MediaController`, synchronizing position, duration, song, queue, and 3-state repeat/shuffle state flows.

### 20. AlarmScheduler.kt & AlarmReceiver.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/alarm/AlarmScheduler.kt` / `AlarmReceiver.kt`
- Schedules exact alarms via system `AlarmManager` (`setAlarmClock`) and receives broadcasts acquiring partial CPU WakeLock.

### 21. AlarmActivity.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/alarm/AlarmActivity.kt`
- Full-screen lock screen Activity displayed on alarm trigger, playing song/playlist/random-liked music looping via ExoPlayer.

---

## Stage 4: UI, ViewModels, Theme & Utilities

### 22. MainScreen.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/ui/screens/MainScreen.kt`
- Root layout container housing ModalNavigationDrawer, Navigation 3 `NavDisplay`, persistent `MiniPlayer`, and `QueueSheet`.

### 23. Theme.kt & Color.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/ui/theme/Theme.kt` / `Color.kt`
- Soft Material 3 color schemes for `YT_DARK`, `PASTEL_MIDNIGHT`, `WARM_AMBER`, `NORDIC_SLATE` themes and root `TGMusicAITheme` composable wrapper.

### 24. AiMetadataCleaner.kt
- **Path**: `app/src/main/java/com/example/tgmusicai/data/local/AiMetadataCleaner.kt`
- `cleanOffline(rawTitle: String, rawArtist: String?)`: Fast (< 1 ms), zero-memory regex parser for title, producer, and featured artist cleaning.
- `fetchOnlineMetadata()`: Transient HTTP POST to Gemini or OpenAI API for metadata enrichment with zero idle memory overhead.
