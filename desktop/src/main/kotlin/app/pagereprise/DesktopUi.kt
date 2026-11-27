package app.pagereprise

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.pow

internal class DesktopHost(val window: JFrame, val state: WindowState, val back: MutableList<() -> Unit>)
internal val LocalDesktop = staticCompositionLocalOf<DesktopHost?> { null }
private val imageExtensions = setOf("jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff")

internal fun transferImages(data: Transferable): List<InputFile> {
    if (data.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val files = data.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: emptyList<Any>()
        val images = files.filterIsInstance<File>().filter { it.isFile && it.extension.lowercase() in imageExtensions }
        if (images.isNotEmpty()) return images.map { InputFile(it::inputStream) }
    }
    if (!data.isDataFlavorSupported(DataFlavor.imageFlavor)) return emptyList()
    val image = data.getTransferData(DataFlavor.imageFlavor) as? Image ?: return emptyList()
    return listOf(InputFile {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        require(width > 0 && height > 0 && width.toLong() * height <= 48_000_000) { "截图过大，请先裁切" }
        val bitmap = if (image is BufferedImage) image else BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
            val g = it.createGraphics()
            try { g.drawImage(image, 0, 0, null) } finally { g.dispose() }
        }
        val bytes = ByteArrayOutputStream()
        check(ImageIO.write(bitmap, "png", bytes))
        bytes.toByteArray().inputStream()
    })
}

internal fun pasteImages(model: StudyModel, explicit: Boolean = false): Boolean {
    val files = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null)?.let(::transferImages).orEmpty() }
        .getOrElse { if (explicit) model.message = "暂时无法读取剪贴板，请再试一次"; return false }
    if (files.isEmpty()) { if (explicit) model.message = "剪贴板中没有图片"; return false }
    model.shared(files)
    return true
}

@Composable
internal fun CaptureTools(model: StudyModel) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { pasteImages(model, true) }, enabled = !model.busy) { Text("粘贴截图  Ctrl+V") }
        Text("也可拖入图片", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun imageFileChooser(): JFileChooser {
    val pictures = runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Pictures)) }.getOrNull()
    val initial = pictures?.takeIf(File::isDirectory)
        ?: File(System.getProperty("user.home"), "Pictures").takeIf(File::isDirectory)
    return JFileChooser(initial).apply {
        dialogTitle = "选择题图"
        isMultiSelectionEnabled = true
        fileFilter = FileNameExtensionFilter("题图", *imageExtensions.toTypedArray())
    }
}

@Composable
internal fun rememberImagePicker(receive: (List<InputFile>) -> Unit): () -> Unit {
    val parent = LocalDesktop.current?.window
    return {
        val chooser = imageFileChooser()
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            receive(chooser.selectedFiles.map { InputFile(it::inputStream) })
        }
    }
}

@Composable
internal fun rememberBackupExporter(receive: (OutputFile) -> Unit): (String) -> Unit {
    val parent = LocalDesktop.current?.window
    return { name ->
        val chooser = JFileChooser().apply {
            dialogTitle = "导出整库备份"
            selectedFile = File(name)
            fileFilter = FileNameExtensionFilter("ZIP 备份", "zip")
        }
        if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            val chosen = chooser.selectedFile
            val file = if (chosen.extension.equals("zip", true)) chosen else File(chosen.parentFile, chosen.name + ".zip")
            if (!file.exists() || JOptionPane.showConfirmDialog(parent, "替换已有备份「" + file.name + "」？", "导出备份",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) receive(OutputFile { writeAtomically(file, it) })
        }
    }
}

@Composable
internal fun rememberBackupImporter(receive: (InputFile) -> Unit): () -> Unit {
    val parent = LocalDesktop.current?.window
    return {
        val chooser = JFileChooser().apply { dialogTitle = "选择循页备份"; fileFilter = FileNameExtensionFilter("ZIP 备份", "zip") }
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) receive(InputFile(chooser.selectedFile::inputStream))
    }
}

@Composable
internal fun PlatformBackHandler(enabled: Boolean, back: () -> Unit) {
    val host = LocalDesktop.current
    val latest by rememberUpdatedState(back)
    DisposableEffect(enabled, host) {
        val action: () -> Unit = { latest() }
        if (enabled) host?.back?.add(action)
        onDispose { host?.back?.remove(action) }
    }
}

@Composable
internal fun ImmersivePhoto() {
    val state = LocalDesktop.current?.state ?: return
    DisposableEffect(state) {
        val previous = state.placement
        state.placement = WindowPlacement.Fullscreen
        onDispose { state.placement = previous }
    }
}

@Composable
internal fun Modifier.mousePhotoGestures(scale: () -> Float, change: (Float, Offset) -> Unit): Modifier {
    val zoom by rememberUpdatedState(scale)
    val transform by rememberUpdatedState(change)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                when {
                    event.type == PointerEventType.Scroll -> {
                        val factor = 1.20f.pow(-mouse.scrollDelta.y.coerceIn(-8f, 8f))
                        val next = (zoom() * factor).coerceIn(1f, 6f)
                        val anchor = Offset(size.width / 2f, size.height / 2f) - mouse.position
                        transform(factor, anchor * (next / zoom() - 1f))
                        mouse.consume()
                    }
                    event.type == PointerEventType.Move && mouse.pressed && zoom() > 1f -> {
                        transform(1f, mouse.position - mouse.previousPosition)
                        mouse.consume()
                    }
                }
            }
        }
    }
}
