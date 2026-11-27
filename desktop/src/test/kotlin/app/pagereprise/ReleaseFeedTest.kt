package app.pagereprise

import app.pagereprise.core.*
import com.sun.net.httpserver.HttpServer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class ReleaseFeedTest {
    @get:Rule val tmp = TemporaryFolder()
    private val bytes = ByteArray(64000) { (it % 251).toByte() }
    private val asset = ReleaseFile("PageReprise-0.4.0.apk", bytes.size.toLong(), digest(bytes))
    private val release = Release("0.4.0", 5, asset, asset.copy(name = "PageReprise-0.4.0-Windows-x64.exe"))
    private lateinit var server: HttpServer
    private lateinit var feed: ReleaseFeed

    @Before fun open() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        feed = ReleaseFeed("http://127.0.0.1:" + server.address.port) {
            check(it.contentEquals("fixture".toByteArray()))
            release
        }
        server.createContext("/latest/download/PageReprise-update.json") { request ->
            val data = "fixture".toByteArray()
            request.sendResponseHeaders(200, data.size.toLong())
            request.responseBody.use { it.write(data) }
        }
        server.start()
    }

    @After fun close() { server.stop(0) }

    @Test fun resumesDownloadsChecksSignedHashAndReusesOnlyVerifiedCache() {
        val calls = AtomicInteger()
        server.createContext("/download/v0.4.0/" + asset.name) { request ->
            calls.incrementAndGet()
            val start = request.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            if (start > 0) request.responseHeaders.set("Content-Range", "bytes " + start + "-" + (bytes.size - 1) + "/" + bytes.size)
            request.sendResponseHeaders(if (start > 0) 206 else 200, (bytes.size - start).toLong())
            request.responseBody.use { it.write(bytes, start, bytes.size - start) }
        }
        val dir = tmp.newFolder()
        val part = File(dir, asset.name + ".part")
        part.writeBytes(bytes.copyOf(9093))
        assertEquals(release, feed.latest())
        val prepared = feed.download(release, Edition.ANDROID, dir) {}
        assertTrue(asset.matches(prepared.file))
        assertFalse(part.exists())
        feed.download(release, Edition.ANDROID, dir) {}
        assertEquals(1, calls.get())
        prepared.file.writeBytes(ByteArray(bytes.size))
        feed.download(release, Edition.ANDROID, dir) {}
        assertEquals(2, calls.get())
        assertTrue(asset.matches(prepared.file))
    }

    @Test fun discardsSameSizeCorruptedDownloadAndCanRetryAfterRangeRefusal() {
        val dir = tmp.newFolder()
        File(dir, asset.name + ".part").writeBytes(bytes.copyOf(300))
        val good = AtomicInteger()
        server.createContext("/download/v0.4.0/" + asset.name) { request ->
            if (request.requestHeaders.getFirst("Range") != null) {
                request.sendResponseHeaders(416, -1)
                request.close()
            } else {
                request.sendResponseHeaders(200, bytes.size.toLong())
                request.responseBody.use { it.write(if (good.get() == 0) ByteArray(bytes.size) else bytes) }
            }
        }
        assertThrows(IOException::class.java) { feed.download(release, Edition.ANDROID, dir) {} }
        assertFalse(File(dir, asset.name).exists())
        good.set(1)
        assertTrue(asset.matches(feed.download(release, Edition.ANDROID, dir) {}.file))
    }
}
