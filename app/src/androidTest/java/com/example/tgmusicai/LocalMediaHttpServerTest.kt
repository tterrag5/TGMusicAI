package com.example.tgmusicai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tgmusicai.cast.LocalMediaHttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Exercises the server a Cast device fetches local audio from.
 *
 * An actual Chromecast on the network is genuinely unavailable here, but almost nothing about this
 * component is Cast-specific: it is an HTTP server, and what a Cast device does to it -- a HEAD to
 * check type and size, a GET, and Range requests to seek -- is exactly what this test does. What
 * remains unproven afterwards is only whether a real receiver likes the response, not whether the
 * response is correct.
 *
 * The security properties matter as much as the correctness ones. This is a listening socket on
 * the user's network, so "only serves what was registered" is a behaviour worth a test rather than
 * a comment.
 */
@RunWith(AndroidJUnit4::class)
class LocalMediaHttpServerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: LocalMediaHttpServer
    private lateinit var mediaFile: File
    private lateinit var payload: ByteArray

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        // Large enough that Range requests carve out something meaningful, and random so a
        // byte-for-byte comparison is a real check rather than one satisfied by any zero buffer.
        payload = Random(7).nextBytes(256 * 1024)
        mediaFile = File(context.cacheDir, "served_media.mp3").apply { writeBytes(payload) }

        server = LocalMediaHttpServer(context)
        assertTrue("The media server did not start", server.start())
    }

    /**
     * The server advertises its LAN address, which is what a Cast device must connect to. Tests
     * run inside the app's own process, where cleartext is permitted only for loopback, so the
     * host is swapped while the port and path -- the parts under test -- are kept exactly as the
     * server produced them.
     */
    private fun loopback(url: String): String =
        url.replace(Regex("^http://[^:/]+"), "http://127.0.0.1")

    @After
    fun tearDown() {
        server.close()
        runCatching { mediaFile.delete() }
    }

    @Test
    fun aRegisteredFileIsServedByteForByte() {
        val registered = server.register(android.net.Uri.fromFile(mediaFile))
        assertNotNull("Registering a file produced no URL", registered)
        val url = loopback(registered!!)

        val body = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(payload.size.toString(), response.header("Content-Length"))
            // Cast refuses media whose type it does not recognise, so this header is load-bearing.
            assertTrue(
                "Unexpected content type ${response.header("Content-Type")}",
                response.header("Content-Type")?.startsWith("audio/") == true
            )
            assertEquals("bytes", response.header("Accept-Ranges"))
            response.body?.bytes()
        }

        assertArrayEquals("Served bytes differ from the file on disk", payload, body)
    }

    @Test
    fun aRangeRequestReturnsExactlyTheRequestedSlice() {
        // This is how a Cast device seeks. Without it, seeking within a cast track is impossible.
        val url = loopback(server.register(android.net.Uri.fromFile(mediaFile))!!)
        val start = 1000L
        val end = 4999L

        val body = client.newCall(
            Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        ).execute().use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes $start-$end/${payload.size}", response.header("Content-Range"))
            assertEquals((end - start + 1).toString(), response.header("Content-Length"))
            response.body?.bytes()
        }

        assertArrayEquals(
            payload.copyOfRange(start.toInt(), end.toInt() + 1),
            body
        )
    }

    @Test
    fun anOpenEndedRangeRunsToTheEndOfTheFile() {
        // "bytes=N-" is the common shape when a player resumes a stream it already started.
        val url = loopback(server.register(android.net.Uri.fromFile(mediaFile))!!)
        val start = payload.size - 2048L

        val body = client.newCall(
            Request.Builder().url(url).header("Range", "bytes=$start-").build()
        ).execute().use { response ->
            assertEquals(206, response.code)
            response.body?.bytes()
        }

        assertArrayEquals(payload.copyOfRange(start.toInt(), payload.size), body)
    }

    @Test
    fun aRangeBeyondTheEndIsClampedRatherThanFailing() {
        val url = loopback(server.register(android.net.Uri.fromFile(mediaFile))!!)

        val body = client.newCall(
            Request.Builder().url(url).header("Range", "bytes=0-${payload.size + 50_000}").build()
        ).execute().use { response ->
            assertEquals(206, response.code)
            response.body?.bytes()
        }

        assertEquals(payload.size, body?.size)
    }

    @Test
    fun aHeadRequestReportsSizeAndTypeWithoutABody() {
        // A Cast device issues this before committing to play, so the headers have to be right
        // even though nothing is sent back.
        val url = loopback(server.register(android.net.Uri.fromFile(mediaFile))!!)

        client.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(payload.size.toString(), response.header("Content-Length"))
            assertTrue(response.header("Content-Type")?.startsWith("audio/") == true)
            assertEquals(0L, response.body?.contentLength() ?: 0L)
        }
    }

    @Test
    fun anUnregisteredTokenIsRefused() {
        // The security property: being on the same network must not be enough to read arbitrary
        // files. Only what was explicitly registered is reachable.
        val url = loopback(server.register(android.net.Uri.fromFile(mediaFile))!!)
        val forged = url.substringBeforeLast('/') + "/0123456789abcdef0123456789abcdef"

        client.newCall(Request.Builder().url(forged).build()).execute().use { response ->
            assertEquals(404, response.code)
        }
    }

    @Test
    fun registeringTheSameFileTwiceReusesOneToken() {
        // Re-casting the same track repeatedly must not grow the registration map without bound
        // over a long listening session.
        val first = server.register(android.net.Uri.fromFile(mediaFile))
        val second = server.register(android.net.Uri.fromFile(mediaFile))
        assertEquals(first, second)
    }

    @Test
    fun closingTheServerStopsServing() {
        // Registrations are dropped when the Cast session ends; nothing should stay reachable on
        // the network afterwards.
        val url = loopback(server.register(android.net.Uri.fromFile(mediaFile))!!)
        server.close()

        val reachable = try {
            client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }

        assertTrue("The server kept serving after being closed", !reachable)
        assertNull("A closed server still reports a port", server.port)
    }
}
