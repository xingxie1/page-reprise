package app.pagereprise

import androidx.compose.runtime.*
import app.pagereprise.core.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

internal data class PreparedUpdate(val release: Release, val asset: ReleaseFile, val file: File)

internal class ReleaseFeed(
    private val root: String = "https://github.com/xingxie1/page-reprise/releases",
    private val verify: (ByteArray) -> Release = Release::verify
) {
    private val http = OkHttpClient.Builder().followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    private fun get(url: String, offset: Long = 0): Response {
        val req = Request.Builder().url(url).header("User-Agent", "PageReprise-Updater")
            .header("Cache-Control", "no-cache").header("Accept-Encoding", "identity")
        if (offset > 0) req.header("Range", "bytes=$offset-")
        val response = http.newCall(req.build()).execute()
        if (response.isSuccessful || (offset > 0 && response.code == 416)) return response
        response.close()
        throw IOException("更新服务返回 HTTP " + response.code)
    }

    fun latest(): Release = get("$root/latest/download/PageReprise-update.json").use { response ->
        val bytes = ByteArrayOutputStream()
        requireNotNull(response.body).byteStream().use { Backup.copyLimited(it, bytes, 32768) }
        verify(bytes.toByteArray())
    }

    fun download(release: Release, edition: Edition, directory: File, progress: (Int) -> Unit): PreparedUpdate {
        val asset = release.file(edition)
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, asset.name)
        val prepared = PreparedUpdate(release, asset, target)
        if (asset.matches(target)) return prepared
        val part = File(directory, asset.name + ".part")
        val offset = part.length().takeIf { it in 1 until asset.size } ?: 0L
        val url = "$root/download/v" + release.version + "/" + asset.name
        val ranged = get(url, offset)
        val result = if (ranged.code == 416) { ranged.close(); part.delete(); get(url) } else ranged
        result.use { response ->
            val append = response.code == 206
            if (append) {
                val expectedRange = "bytes " + offset + "-" + (asset.size - 1) + "/" + asset.size
                if (response.header("Content-Range") != expectedRange) {
                    part.delete()
                    throw IOException("更新包分段不完整，请重试")
                }
            } else require(response.code == 200)
            var received = if (append) offset else 0L
            var reported = -1
            requireNotNull(response.body).byteStream().use { input ->
                java.io.FileOutputStream(part, append).use { output ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        received += n
                        if (received > asset.size) throw IOException("更新包大小不匹配")
                        output.write(bytes, 0, n)
                        val percent = (100 * received / asset.size).toInt()
                        if (percent != reported) { progress(percent); reported = percent }
                    }
                }
            }
            if (received != asset.size) throw IOException("更新下载中断，可继续下载")
        }
        if (!asset.matches(part)) { part.delete(); throw IOException("更新包校验失败，请重试") }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return prepared
    }
}

class AppUpdates internal constructor(
    val installed: String,
    private val edition: Edition,
    private val directory: File,
    private val scope: CoroutineScope,
    private val androidCode: Int = 0,
    private val feed: ReleaseFeed = ReleaseFeed()
) {
    var status by mutableStateOf("打开循页时自动检查"); private set
    var downloading by mutableStateOf(false); private set
    var installing by mutableStateOf(false); private set
    internal var prepared by mutableStateOf<PreparedUpdate?>(null); private set
    var active by mutableStateOf(false); private set
    var autoInstall by mutableStateOf(false); private set
    var manual by mutableStateOf(false); private set
    private var checking = false
    private var lastCheck = 0L
    private var postponed: String? = null
    private val attempt get() = File(directory, "last-install")
    private var watchdog: Job? = null

    fun foreground() { active = true; check(false) }
    fun background() { active = false }

    fun check(explicit: Boolean = true) {
        if (checking || installing || (!explicit && prepared != null && autoInstall)) return
        val now = System.nanoTime()
        if (!explicit && lastCheck != 0L && now - lastCheck < 30_000_000_000L) return
        lastCheck = now
        checking = true
        autoInstall = false
        manual = explicit
        if (explicit) postponed = null
        status = "正在检查更新…"
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { feed.latest() }
                if (!release.newerThan(installed) || (edition == Edition.ANDROID && release.androidCode <= androidCode)) {
                    prepared = null
                    status = "已是最新版本"
                    withContext(Dispatchers.IO) { cleanInstalled() }
                    return@launch
                }
                downloading = true
                status = "正在下载 " + release.version
                val update = withContext(Dispatchers.IO) {
                    feed.download(release, edition, directory) { percent ->
                        scope.launch { if (downloading) status = "正在下载 " + release.version + " · " + percent + "%" }
                    }
                }
                val recentAttempt = withContext(Dispatchers.IO) {
                    attempt.isFile && attempt.readText() == release.version &&
                        System.currentTimeMillis() - attempt.lastModified() in 0 until 3_600_000L
                }
                prepared = update
                status = "更新 " + release.version + " 已下载"
                autoInstall = explicit || (!recentAttempt && postponed != release.version)
                if (!autoInstall && recentAttempt) status = "上次安装尚未完成，可重试"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                prepared = null
                status = "更新检查未完成 · " + (e.localizedMessage ?: "请检查网络")
            } finally { downloading = false; checking = false }
        }
    }

    private fun cleanInstalled() {
        for (file in directory.listFiles().orEmpty()) {
            val version = Regex("PageReprise-([0-9]+\\.[0-9]+\\.[0-9]+)(?:-Windows-x64\\.exe|\\.apk)(?:\\.part)?")
                .matchEntire(file.name)?.groupValues?.get(1) ?: continue
            if (Release.compareVersions(version, installed) <= 0) file.delete()
        }
        if (attempt.isFile && runCatching { Release.compareVersions(attempt.readText(), installed) <= 0 }.getOrDefault(false)) attempt.delete()
    }

    fun later() {
        postponed = prepared?.release?.version
        autoInstall = false
        manual = false
    }

    internal fun begin(install: suspend (PreparedUpdate) -> Unit) {
        val update = prepared ?: return
        if (installing || !autoInstall) return
        autoInstall = false
        installing = true
        status = "正在安装 " + update.release.version
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(update.asset.matches(update.file)) { "更新包校验失败" }
                    attempt.writeText(update.release.version)
                }
                install(update)
                watchdog?.cancel()
                watchdog = scope.launch {
                    delay(120000)
                    if (installing) finished("系统尚未完成安装，可以重试")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { finished(e.localizedMessage ?: "安装失败，请重试") }
        }
    }

    fun finished(text: String) {
        watchdog?.cancel()
        installing = false
        later()
        status = text
    }
}
