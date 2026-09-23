package com.example.tgmusicai

import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.MusicFolderTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers turning a flat library into the folder structure it came from.
 *
 * The shaping is where this can go wrong invisibly: a tree rooted at a path that is not a real
 * directory, or a folder that reports itself empty because its tracks are one level down, both
 * render as a browser the user cannot get anything out of.
 */
class MusicFolderTreeTest {

    private var nextId = 1L

    private fun song(folder: String?, title: String = "Track ${nextId}"): Song = Song(
        id = nextId++,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = 1000L,
        mediaUri = "file:///$folder/$title.mp3",
        folderPath = folder
    )

    @Test
    fun `the root is the deepest folder every track shares`() {
        // Not the filesystem root: starting at "/" would make the user tap through storage,
        // emulated and 0 before reaching anything real.
        val songs = listOf(
            song("/storage/emulated/0/Music/Rock"),
            song("/storage/emulated/0/Music/Jazz")
        )

        val tree = MusicFolderTree.build(songs)

        assertEquals("/storage/emulated/0/Music", tree.path)
        assertEquals(setOf("Rock", "Jazz"), tree.subfolders.map { it.name }.toSet())
    }

    @Test
    fun `the common prefix is computed by path segment, not by character`() {
        // "/music/rock" and "/music/rap" share the characters "/music/r", which is not a folder.
        val prefix = MusicFolderTree.commonPrefixPath(listOf("/music/rock", "/music/rap"))
        assertEquals("/music", prefix)
    }

    @Test
    fun `a single folder is shown from its parent so there is somewhere to navigate`() {
        val songs = listOf(song("/storage/Music/Albums"))

        val tree = MusicFolderTree.build(songs)

        assertEquals("/storage/Music", tree.path)
        assertEquals(listOf("Albums"), tree.subfolders.map { it.name })
    }

    @Test
    fun `only immediate children are listed as subfolders`() {
        // A deeper folder belongs to whichever child contains it, and is reached by opening that
        // child -- listing it at the top would flatten the tree into a list of full paths.
        val songs = listOf(
            song("/m/Rock"),
            song("/m/Rock/Live"),
            song("/m/Rock/Live/1994"),
            song("/m/Jazz")
        )

        val tree = MusicFolderTree.build(songs)

        assertEquals(setOf("Rock", "Jazz"), tree.subfolders.map { it.name }.toSet())
        val rock = tree.subfolders.first { it.name == "Rock" }
        assertEquals(listOf("Live"), rock.subfolders.map { it.name })
        assertEquals(listOf("1994"), rock.subfolders.first().subfolders.map { it.name })
    }

    @Test
    fun `a folder's count includes everything beneath it`() {
        // A folder holding only subfolders would otherwise read as empty and look broken.
        val songs = listOf(
            song("/m/Rock/Live"),
            song("/m/Rock/Live"),
            song("/m/Rock/Studio"),
            song("/m/Jazz")
        )

        val tree = MusicFolderTree.build(songs)
        val rock = tree.subfolders.first { it.name == "Rock" }

        assertEquals(0, rock.songs.size)
        assertEquals(3, rock.totalSongCount)
    }

    @Test
    fun `tracks sit in the folder they came from, not in its parent`() {
        // Every track here is under /m/Rock, so that is the deepest shared folder and becomes the
        // root -- the user opens the browser already inside it rather than one level above.
        val songs = listOf(
            song("/m/Rock", title = "Direct"),
            song("/m/Rock/Live", title = "Nested")
        )

        val tree = MusicFolderTree.build(songs)

        assertEquals("/m/Rock", tree.path)
        assertEquals(listOf("Direct"), tree.songs.map { it.title })
        assertEquals(listOf("Nested"), tree.subfolders.single().songs.map { it.title })
    }

    @Test
    fun `cloud-only tracks with no folder are left out`() {
        // They have no folder to be browsed in; including them would invent one.
        val songs = listOf(
            song("/m/Rock"),
            song(null, title = "CloudOnly"),
            song("", title = "Unresolvable")
        )

        val tree = MusicFolderTree.build(songs)

        assertEquals("/m", tree.path)
        assertEquals(1, tree.totalSongCount)
    }

    @Test
    fun `a library with no folders at all produces an empty tree rather than failing`() {
        val tree = MusicFolderTree.build(listOf(song(null), song(null)))
        assertEquals(0, tree.totalSongCount)
        assertTrue(tree.subfolders.isEmpty())
    }

    @Test
    fun `a folder can be looked up by path`() {
        // The browser resolves the open folder against a freshly built tree on every change, so
        // this lookup is what keeps it from stranding on a node that no longer exists.
        val tree = MusicFolderTree.build(
            listOf(song("/m/Rock/Live"), song("/m/Jazz"))
        )

        assertNotNull(MusicFolderTree.findNode(tree, "/m/Rock/Live"))
        assertEquals("Live", MusicFolderTree.findNode(tree, "/m/Rock/Live")?.name)
        assertNull(MusicFolderTree.findNode(tree, "/m/DoesNotExist"))
    }

    @Test
    fun `a trailing slash does not create a separate folder`() {
        val songs = listOf(song("/m/Rock/"), song("/m/Rock"))

        val tree = MusicFolderTree.build(songs)

        assertEquals(1, tree.subfolders.size)
        assertEquals(2, tree.subfolders.first().songs.size)
    }
}
