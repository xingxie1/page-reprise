package app.pagereprise.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BundleIndex(val version: Int, val owner: String, val savedAt: Long, val events: List<Event>)
data class Staged(val index: BundleIndex, val directory: File) : AutoCloseable {
    override fun close() { directory.deleteRecursively() }
}

fun Collection<Event>.images(): Set<String> = flatMap { event ->
    event.body?.let { it.pictures + it.answers }.orEmpty()
}.toSet()

object Backup {
    const val imageLimit = 16 * 1024 * 1024
    private const val manifestLimit = 32 * 1024 * 1024
    private const val totalLimit = 1024L * 1024 * 1024
    private val imageName = Regex("images/([0-9a-f]{64})")

    fun write(output: OutputStream, owner: String, events: List<Event>, source: (String) -> InputStream) {
        val manifest = Codec.json.toJson(BundleIndex(1, owner, System.currentTimeMillis(), events)).toByteArray()
        val images = events.images()
        require(manifest.size <= manifestLimit && images.size < 16000) { "备份超出当前大小限制" }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            var total = manifest.size.toLong()
            images.sorted().forEach { hash ->
                zip.putNextEntry(ZipEntry("images/" + hash))
                source(hash).use { total += copyLimited(it, zip, imageLimit) }
                require(total <= totalLimit) { "备份超过 1 GiB" }
                zip.closeEntry()
            }
        }
    }

    fun preview(input: InputStream, owner: String, cache: File): Staged {
        val directory = File(cache, "restore-" + newId())
        require(directory.mkdirs())
        try {
            var index: BundleIndex? = null
            var total = 0L
            val names = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && names.size < 16000 && names.add(entry.name)) { "备份条目无效" }
                    if (entry.name == "manifest.json") {
                        val buffer = java.io.ByteArrayOutputStream()
                        total += copyLimited(zip, buffer, manifestLimit)
                        val header = Codec.json.fromJson(buffer.toString("UTF-8"), BundleIndex::class.java)
                        require(header.version == 1 && header.owner == owner) { "版本不兼容或不是当前账号的备份" }
                        require(header.events.size <= 150000)
                        header.events.forEach { it.checked() }
                        index = header
                    } else {
                        val hash = imageName.matchEntire(entry.name)?.groupValues?.get(1) ?: error("备份路径无效")
                        val digest = MessageDigest.getInstance("SHA-256")
                        File(directory, hash).outputStream().use { file ->
                            total += copyLimited(zip, file, imageLimit, digest)
                        }
                        require(digest.digest().hex() == hash) { "题图校验失败" }
                    }
                    require(total <= totalLimit) { "备份超过 1 GiB" }
                    zip.closeEntry()
                }
            }
            val header = requireNotNull(index) { "缺少备份清单" }
            require(header.events.map { it.id }.distinct().size == header.events.size) { "备份中有重复操作标识" }
            require(names - "manifest.json" == header.events.images().map { "images/" + it }.toSet()) { "题图缺失或与清单不符" }
            return Staged(header, directory)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    fun copyLimited(input: InputStream, output: OutputStream, limit: Int, digest: MessageDigest? = null): Long {
        val buffer = ByteArray(32768)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return total
            total += count
            require(total <= limit) { "文件过大或已损坏" }
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
        }
    }
}
