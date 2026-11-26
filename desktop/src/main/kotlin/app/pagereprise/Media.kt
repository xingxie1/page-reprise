package app.pagereprise

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.Color
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.roundToInt

internal fun sampledImage(file: File, limit: Int): BufferedImage =
    ImageIO.createImageInputStream(file).use { stream ->
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: error("无法读取图片，请选择 PNG 或 JPEG 截图")
        try {
            reader.input = stream
            val edge = maxOf(reader.getWidth(0), reader.getHeight(0))
            var sample = 1
            while (edge / sample > limit) sample *= 2
            reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
        } finally { reader.dispose() }
    }

internal fun decodePhoto(file: File, limit: Int) = runCatching { sampledImage(file, limit).toComposeImageBitmap() }.getOrNull()

class Media(directory: File, owner: String) : MediaFiles(directory, File(directory, "cache").apply { mkdirs() }, owner) {
    override fun normalize(file: File): String {
        val image = sampledImage(file, 3600)
        val orientation = runCatching {
            ImageMetadataReader.readMetadata(file).getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
        }.getOrNull() ?: 1
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
            else -> AffineTransform()
        }
        val swapped = orientation in 5..8
        val solid = BufferedImage(if (swapped) image.height else image.width, if (swapped) image.width else image.height, BufferedImage.TYPE_INT_RGB)
        val graphics = solid.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, solid.width, solid.height)
            graphics.drawImage(image, transform, null)
        } finally { graphics.dispose() }
        return persist(solid)
    }

    override fun crop(hash: String, left: Float, top: Float, right: Float, bottom: Float): String {
        val image = sampledImage(file(hash), 3600)
        val x = (image.width * left).roundToInt().coerceIn(0, image.width - 1)
        val y = (image.height * top).roundToInt().coerceIn(0, image.height - 1)
        val endX = (image.width * right).roundToInt().coerceIn(x + 1, image.width)
        val endY = (image.height * bottom).roundToInt().coerceIn(y + 1, image.height)
        return persist(image.getSubimage(x, y, endX - x, endY - y))
    }

    private fun persist(image: BufferedImage) = writeJpeg { output ->
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                val options = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = .94f
                }
                writer.write(null, IIOImage(image, null, null), options)
            }
        } finally { writer.dispose() }
    }
}
