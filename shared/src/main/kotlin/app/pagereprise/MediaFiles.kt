package app.pagereprise

import app.pagereprise.core.*
import java.io.*
import java.security.MessageDigest

abstract class MediaFiles(dataDir: File, private val cacheDir: File, private val owner: String) {
    companion object {
        private val editing = mutableMapOf<String, Set<String>>()
        @Synchronized fun protect(owner: String, body: Body?) {
            if (body == null) editing.remove(owner) else editing[owner] = (body.pictures + body.answers).toSet()
        }
    }
    fun removeIfUnused(hash: String) = synchronized(Companion) {
        hash !in editing[owner].orEmpty() && file(hash).let { !it.exists() || it.delete() }
    }
    val root = File(dataDir, "media/" + digest(owner.toByteArray())).also { it.mkdirs() }
    fun file(hash: String): File {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return File(root, hash)
    }

    fun accept(hash: String, input: InputStream) {
        val target = file(hash)
        val tmp = File.createTempFile("download-", ".part", root)
        try {
            val hasher = MessageDigest.getInstance("SHA-256")
            tmp.outputStream().use { Backup.copyLimited(input, it, Backup.imageLimit, hasher) }
            require(hasher.digest().hex() == hash) { "题图校验失败" }
            if (!target.exists()) check(tmp.renameTo(target) || target.exists())
        } finally { tmp.delete() }
    }

    fun import(source: InputFile): String {
        val tmp = File.createTempFile("import-", ".part", cacheDir)
        try {
            source.open().use { input -> tmp.outputStream().use { Backup.copyLimited(input, it, 32 * 1024 * 1024) } }
            return normalize(tmp)
        } finally { tmp.delete() }
    }

    protected abstract fun normalize(file: File): String
    abstract fun crop(hash: String, left: Float, top: Float, right: Float, bottom: Float): String

    protected fun writeJpeg(encode: (OutputStream) -> Unit): String {
        val tmp = File.createTempFile("capture-", ".part", root)
        try {
            val hasher = MessageDigest.getInstance("SHA-256")
            tmp.outputStream().use { out ->
                java.security.DigestOutputStream(out, hasher).use(encode)
            }
            require(tmp.length() <= Backup.imageLimit)
            val hash = hasher.digest().hex()
            val target = file(hash)
            synchronized(Companion) {
                if (!target.exists()) check(tmp.renameTo(target) || target.exists())
                editing[owner] = editing[owner].orEmpty() + hash
            }
            return hash
        } finally { tmp.delete() }
    }
}
