package app.pagereprise

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import app.pagereprise.core.Body
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.*
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

private fun dataDirectory() = File(requireNotNull(System.getenv("LOCALAPPDATA")), "PageReprise/data")

fun main(args: Array<String>) {
    if (args.size == 2 && args.first() == "--collect-trash") {
        val directory = File(args[1])
        val owner = AppPreferences(File(directory, "preferences.properties")).getString("guest", null) ?: return
        val platform = StudyPlatform(directory)
        platform.store.use { store -> runBlocking { store.maintain(owner)?.let { platform.scheduleCleanup(owner, it) } } }
        return
    }
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val directory = dataDirectory().apply { mkdirs() }
    try {
        FileChannel.open(File(directory, "window.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock()
            if (lock == null) {
                User32.INSTANCE.FindWindow(null, "循页")?.let { window ->
                    val placement = WinUser.WINDOWPLACEMENT()
                    if (User32.INSTANCE.GetWindowPlacement(window, placement).booleanValue() && placement.showCmd == WinUser.SW_SHOWMINIMIZED) {
                        User32.INSTANCE.ShowWindow(window, WinUser.SW_RESTORE)
                    }
                    User32.INSTANCE.SetForegroundWindow(window)
                }
                return
            }
            lock.use { openDesktop(StudyPlatform(directory)) }
        }
    } catch (failure: Exception) {
        JOptionPane.showMessageDialog(null, failure.localizedMessage ?: failure.toString(), "循页无法打开", JOptionPane.ERROR_MESSAGE)
    }
}

private fun openDesktop(platform: StudyPlatform) {
    val lifetime = SupervisorJob()
    val model = StudyModel(platform, CoroutineScope(Dispatchers.Main.immediate + lifetime))
    try {
        application(exitProcessOnExit = false) {
            val bounds = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
            val state = rememberWindowState(size = DpSize(minOf(1120, bounds.width * 9 / 10).dp, minOf(800, bounds.height * 9 / 10).dp))
            val handlers = remember { mutableListOf<() -> Unit>() }
            var confirmClose by remember { mutableStateOf(false) }
            var closeAfterSave by remember { mutableStateOf(false) }
            val close: () -> Unit = {
                when {
                    model.busy -> model.message = "正在保存，请稍候"
                    model.page == Page.EDITOR && model.editor.body != Body() -> confirmClose = true
                    else -> exitApplication()
                }
            }
            Window(onCloseRequest = close, state = state, title = "循页", icon = painterResource("bookmark.png"),
                onPreviewKeyEvent = { key ->
                    when {
                        key.type != KeyEventType.KeyDown -> false
                        key.key == Key.Escape && handlers.isNotEmpty() -> { handlers.last().invoke(); true }
                        key.key == Key.V && key.isCtrlPressed && !key.isAltPressed && !key.isShiftPressed &&
                            model.viewedPhoto == null && model.page in listOf(Page.HOME, Page.BOOK, Page.EDITOR) -> pasteImages(model)
                        else -> false
                    }
                }) {
                val host = remember(window, state) { DesktopHost(window, state, handlers) }
                DisposableEffect(window) {
                    window.minimumSize = Dimension(480, 360)
                    val focus = object : WindowAdapter() { override fun windowGainedFocus(e: WindowEvent) = model.foreground() }
                    window.addWindowFocusListener(focus)
                    val removeDrop = attachDrop(window.contentPane, model)
                    model.foreground()
                    onDispose { removeDrop(); window.removeWindowFocusListener(focus) }
                }
                CompositionLocalProvider(LocalDesktop provides host) {
                    RepriseApp(model)
                    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false },
                        title = { Text("关闭循页前保存这道题？") },
                        confirmButton = { TextButton(onClick = {
                            confirmClose = false
                            if (model.editor.card == null) model.saveDraft() else model.saveCard()
                            closeAfterSave = true
                        }) { Text(if (model.editor.card == null) "暂存草稿并退出" else "保存并退出") } },
                        dismissButton = {
                            TextButton(onClick = { confirmClose = false }) { Text("继续编辑") }
                            TextButton(onClick = ::exitApplication) { Text("放弃改动并退出") }
                        })
                }
                LaunchedEffect(closeAfterSave, model.busy) {
                    if (closeAfterSave && !model.busy) {
                        closeAfterSave = false
                        if (model.page != Page.EDITOR) exitApplication()
                    }
                }
            }
        }
    } finally {
        model.close()
        runBlocking { lifetime.cancelAndJoin() }
        platform.store.close()
    }
}

private fun attachDrop(root: Component, model: StudyModel): () -> Unit {
    val previous = mutableMapOf<Component, DropTarget?>()
    val listener = object : DropTargetAdapter() {
        override fun drop(event: DropTargetDropEvent) {
            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && !event.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                event.rejectDrop()
                return
            }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val images = runCatching { transferImages(event.transferable) }.getOrDefault(emptyList())
            event.dropComplete(images.isNotEmpty())
            SwingUtilities.invokeLater {
                if (images.isEmpty()) model.message = "请选择图片文件" else model.shared(images)
            }
        }
    }
    fun install(component: Component) {
        previous[component] = component.dropTarget
        DropTarget(component, DnDConstants.ACTION_COPY, listener, true)
        if (component is Container) component.components.forEach(::install)
    }
    install(root)
    return { previous.forEach { (component, target) -> component.dropTarget = target } }
}
