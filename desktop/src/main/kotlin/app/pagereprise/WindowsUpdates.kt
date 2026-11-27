package app.pagereprise

import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID

internal val LocalUpdateExit = staticCompositionLocalOf<() -> Unit> { error("缺少桌面窗口") }

internal fun updateInProgress(directory: File): Boolean {
    val file = File(directory, "updates/install.lock")
    if (!file.exists()) return false
    return try {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock() ?: return true
            lock.release()
            false
        }
    } catch (_: IOException) { true }
}

private suspend fun startInstaller(update: PreparedUpdate, launcher: File) = withContext(Dispatchers.IO) {
    val directory = update.file.parentFile
    val helper = File(directory, "PageReprise.Update.exe")
    File(launcher.parentFile, "app/PageReprise.Update.exe").copyTo(helper, overwrite = true)
    val ready = File(directory, UUID.randomUUID().toString() + ".ready")
    val process = ProcessBuilder(helper.path, ProcessHandle.current().pid().toString(), update.file.path,
        update.asset.sha256, ready.path).directory(directory).start()
    try {
        repeat(150) {
            if (ready.isFile) return@withContext
            check(process.isAlive) { "更新安装程序未能启动，请重试" }
            delay(100)
        }
        throw IOException("更新安装程序未响应")
    } finally {
        if (!ready.isFile) process.destroy()
        ready.delete()
    }
}

@Composable
internal fun UpdateHost(model: StudyModel) {
    val updates = model.updates ?: return
    if (!model.canInstallUpdate || !updates.autoInstall) return
    val launcher = remember { File(System.getProperty("jpackage.app-path")).absoluteFile }
    val installed = remember { File(System.getenv("LOCALAPPDATA"), "PageRepriseDesktop/PageReprise.exe") }
    val exit = LocalUpdateExit.current
    val install = { updates.begin { update -> startInstaller(update, launcher); exit() } }
    if (launcher == installed) {
        LaunchedEffect(updates.prepared) { install() }
    } else {
        AlertDialog(onDismissRequest = updates::later, title = { Text("安装新版循页？") },
            text = { Text("当前使用的是便携版。更新会安装到本机并打开新版，题库继续使用原来的位置。") },
            confirmButton = { TextButton(onClick = install) { Text("安装并打开") } },
            dismissButton = { TextButton(onClick = updates::later) { Text("稍后") } })
    }
}
