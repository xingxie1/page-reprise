package app.pagereprise

import app.pagereprise.core.Edition
import kotlinx.coroutines.CoroutineScope
import app.pagereprise.core.Codec
import app.pagereprise.core.digest
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

internal fun writeAtomically(file: File, body: (OutputStream) -> Unit) {
    val target = file.absoluteFile
    val temp = File.createTempFile(".reprise-", ".part", target.parentFile)
    try {
        temp.outputStream().use(body)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { temp.delete() }
}

class AppPreferences(private val file: File) {
    private val values = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
    fun getString(key: String, fallback: String?): String? = values.getProperty(key, fallback)
    fun getLong(key: String, fallback: Long) = values.getProperty(key)?.toLongOrNull() ?: fallback
    fun putString(key: String, value: String) {
        values.setProperty(key, value)
        writeAtomically(file) { values.store(it, null) }
    }
    fun putLong(key: String, value: Long) = putString(key, value.toString())
}

class Vault(private val file: File) {
    fun read(): Session? = secret(file)?.let { Codec.json.fromJson(it, Session::class.java) }
    fun write(session: Session?) = save(file, session?.let(Codec.json::toJson))
    private fun keyFile(owner: String) = File(file.parentFile, "deepseek-" + digest(owner.toByteArray()) + ".dpapi")
    fun readingKey(owner: String) = secret(keyFile(owner))
    fun saveReadingKey(owner: String, value: String?) = save(keyFile(owner), value)
    private fun secret(file: File): String? = if (!file.isFile) null else {
        val plain = Crypt32Util.cryptUnprotectData(file.readBytes())
        try { String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
    }
    private fun save(file: File, value: String?) {
        if (value == null) { Files.deleteIfExists(file.toPath()); return }
        val plain = value.toByteArray(Charsets.UTF_8)
        try {
            val sealed = Crypt32Util.cryptProtectData(plain)
            writeAtomically(file) { it.write(sealed) }
        } finally { plain.fill(0) }
    }
}

class StudyPlatform(val directory: File) {
    val cacheDir = File(directory, "cache").apply { mkdirs() }
    val store = Store(directory)
    val prefs = AppPreferences(File(directory, "preferences.properties"))
    val vault = Vault(File(directory, "session.dpapi"))
    fun media(owner: String) = Media(directory, owner)
    fun updates(scope: CoroutineScope): AppUpdates? = System.getProperty("jpackage.app-version")?.let { version ->
        AppUpdates(version, Edition.WINDOWS, File(directory, "updates"), scope)
    }
    private var scheduled: Long? = null

    @Synchronized fun scheduleCleanup(owner: String, deadline: Long) {
        val executable = System.getProperty("jpackage.app-path") ?: return
        if (scheduled == deadline || !owner.startsWith("local:")) return
        val task = "PageReprise-Trash-" + digest(directory.canonicalPath.toByteArray()).take(16)
        // Task Scheduler uses whole seconds. Round up so collection never runs before expiry.
        val start = Instant.ofEpochSecond(Math.floorDiv(deadline + 999L, 1000L)).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val xml = """<?xml version="1.0" encoding="UTF-16"?>
            <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
              <Triggers><TimeTrigger><StartBoundary>$start</StartBoundary><Enabled>true</Enabled></TimeTrigger></Triggers>
              <Settings><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><StartWhenAvailable>true</StartWhenAvailable></Settings>
              <Actions><Exec><Command>${xmlText(executable)}</Command><Arguments>--collect-trash "${xmlText(directory.canonicalPath)}"</Arguments></Exec></Actions>
            </Task>""".trimIndent()
        val descriptor = File.createTempFile("trash-", ".xml", cacheDir)
        try {
            descriptor.writeText("\uFEFF" + xml, Charsets.UTF_16LE)
            val process = ProcessBuilder("schtasks.exe", "/Create", "/F", "/TN", task, "/XML", descriptor.absolutePath)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) scheduled = deadline
            else process.destroy()
        } finally { descriptor.delete() }
    }

    private fun xmlText(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
