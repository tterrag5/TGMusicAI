package com.example.tgmusicai.data.repository

import com.example.tgmusicai.data.local.entity.Song

/**
 * Turns a flat list of tracks into the folder structure they came from, for browsing the library
 * the way it is laid out on disk.
 *
 * Built from the paths already stored on each track rather than by walking the filesystem. Walking
 * would show directories full of things this app cannot play and, more importantly, would need
 * broad storage access the app deliberately does not hold. Deriving from the library means every
 * folder shown contains something playable, which is the only reason to open one.
 *
 * Pure functions over data, with no Android dependencies, so the tree-shaping logic is unit-tested
 * directly.
 */
object MusicFolderTree {

    /** One directory in the browser: its subfolders, and the tracks sitting directly inside it. */
    data class FolderNode(
        /** Full path of this directory. */
        val path: String,
        /** Just the directory's own name, for display. */
        val name: String,
        /** Immediate subdirectories that contain tracks somewhere beneath them. */
        val subfolders: List<FolderNode>,
        /** Tracks directly in this directory, excluding those in its subfolders. */
        val songs: List<Song>
    ) {
        /** Total tracks here and everywhere below, which is what a folder row should show. */
        val totalSongCount: Int
            get() = songs.size + subfolders.sumOf { it.totalSongCount }
    }

    /**
     * Builds the browsable tree for [songs].
     *
     * The root is the deepest directory that contains every track, rather than the filesystem
     * root. Starting at "/" would make the user tap through "storage", "emulated", "0" and so on
     * before reaching anything, every single time.
     */
    fun build(songs: List<Song>): FolderNode {
        val withFolders = songs.filter { !it.folderPath.isNullOrBlank() }
        if (withFolders.isEmpty()) {
            return FolderNode(path = "", name = "", subfolders = emptyList(), songs = emptyList())
        }

        val byFolder = withFolders.groupBy { it.folderPath!!.trimEnd('/') }
        val rootPath = commonPrefixPath(byFolder.keys)
        return buildNode(rootPath, byFolder)
    }

    /**
     * The deepest directory every path in [paths] sits under.
     *
     * Compared segment by segment rather than character by character: two sibling directories
     * `/music/rock` and `/music/rap` share the character prefix `/music/r`, which is not a
     * directory and would produce a tree rooted at something that does not exist.
     */
    fun commonPrefixPath(paths: Collection<String>): String {
        if (paths.isEmpty()) return ""
        val split = paths.map { it.trim('/').split('/') }
        val first = split.first()
        var shared = 0
        while (shared < first.size && split.all { it.size > shared && it[shared] == first[shared] }) {
            shared++
        }
        // A single folder is its own common prefix; stepping up one level gives the user somewhere
        // to navigate from rather than dropping them straight into the only folder there is.
        val segments = if (shared == first.size && split.size == 1 && shared > 0) {
            first.take(shared - 1)
        } else {
            first.take(shared)
        }
        return "/" + segments.joinToString("/")
    }

    private fun buildNode(path: String, byFolder: Map<String, List<Song>>): FolderNode {
        val normalized = path.trimEnd('/')
        val directSongs = byFolder[normalized].orEmpty()

        // Immediate children only: a folder deeper down belongs to whichever child contains it,
        // and is reached by recursing rather than by being listed here.
        val childPaths = byFolder.keys
            .filter { it.startsWith("$normalized/") && it != normalized }
            .map { candidate ->
                val remainder = candidate.removePrefix("$normalized/")
                "$normalized/${remainder.substringBefore('/')}"
            }
            .distinct()
            .sorted()

        return FolderNode(
            path = normalized,
            name = normalized.substringAfterLast('/').ifEmpty { "/" },
            subfolders = childPaths.map { buildNode(it, byFolder) },
            songs = directSongs.sortedBy { it.title.lowercase() }
        )
    }

    /**
     * Walks to [path] from [root], or null if it is not in the tree. Used to resolve the
     * currently-open folder after the library changes underneath the browser.
     */
    fun findNode(root: FolderNode, path: String): FolderNode? {
        if (root.path == path) return root
        for (child in root.subfolders) {
            findNode(child, path)?.let { return it }
        }
        return null
    }
}
