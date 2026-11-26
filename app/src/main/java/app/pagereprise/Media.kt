package app.pagereprise

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.pagereprise.core.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

class Media(private val context: Context, private val owner: String) {
    companion object {
        private val editing = mutableMapOf<String, Set<String>>()
        @Synchronized fun protect(owner: String, body: Body?) {
            if (body == null) editing.remove(owner) else editing[owner] = (body.pictures + body.answers).toSet()
        }
    }
    fun removeIfUnused(hash: String) = synchronized(Companion) {
        hash !in editing[owner].orEmpty() && file(hash).let { !it.exists() || it.delete() }
    }
    val root = File(context.filesDir, "media/" + digest(owner.toByteArray())).also { it.mkdirs() }
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

    fun import(uri: Uri): String {
        require(uri.scheme == "content") { "请选择系统分享或相册中的图片" }
        val tmp = File.createTempFile("import-", ".part", context.cacheDir)
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                tmp.outputStream().use { Backup.copyLimited(input, it, 32 * 1024 * 1024) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "不是可读取的图片" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3600) sample *= 2
            val bitmap = requireNotNull(BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample }))
            val orientation = runCatching { ExifInterface(tmp.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            return persist(upright)
        } finally { tmp.delete() }
    }

    fun crop(hash: String, left: Float, top: Float, right: Float, bottom: Float): String {
        val original = requireNotNull(BitmapFactory.decodeFile(file(hash).path))
        val x0 = (original.width * left).roundToInt().coerceIn(0, original.width - 1)
        val y0 = (original.height * top).roundToInt().coerceIn(0, original.height - 1)
        val x1 = (original.width * right).roundToInt().coerceIn(x0 + 1, original.width)
        val y1 = (original.height * bottom).roundToInt().coerceIn(y0 + 1, original.height)
        return persist(Bitmap.createBitmap(original, x0, y0, x1 - x0, y1 - y0))
    }

    private fun persist(bitmap: Bitmap): String {
        val solid = if (bitmap.hasAlpha()) Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        } else bitmap
        val tmp = File.createTempFile("capture-", ".part", root)
        try {
            val hasher = MessageDigest.getInstance("SHA-256")
            tmp.outputStream().use { out ->
                java.security.DigestOutputStream(out, hasher).use { check(solid.compress(Bitmap.CompressFormat.JPEG, 94, it)) }
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
