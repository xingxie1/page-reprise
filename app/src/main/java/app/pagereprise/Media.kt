package app.pagereprise

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import kotlin.math.roundToInt

class Media(context: Context, owner: String) : MediaFiles(context.filesDir, context.cacheDir, owner) {
    override fun normalize(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "不是可读取的图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3600) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
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
    }

    override fun crop(hash: String, left: Float, top: Float, right: Float, bottom: Float): String {
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
        return writeJpeg { check(solid.compress(Bitmap.CompressFormat.JPEG, 94, it)) }
    }
}
