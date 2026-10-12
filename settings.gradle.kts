pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor at the pinned commit, vendored: JitPack evicted that build (404 since
        // 2026-10), and nothing else hosts it. Checked before JitPack and only for this module.
        maven {
            url = uri("third_party/maven")
            content { includeModule("com.github.TeamNewPipe", "NewPipeExtractor") }
        }
        maven { url = uri("https://jitpack.io") }
        // Only ever asked for its own group. The host refuses connections, and as an unfiltered
        // repository it was queried for every dynamic version (e.g. adaptive "1.3.+"), which
        // failed online builds outright once Gradle's 24h version-list cache expired.
        maven {
            url = uri("https://maven.schabi.org/repository/releases/")
            content { includeGroupByRegex("org\\.schabi(\\..*)?") }
        }
    }
}

rootProject.name = "TGMusicAI"
include(":app")
 