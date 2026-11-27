package app.pagereprise

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private data class InstallEvent(val result: Int, val confirmation: Intent?)
private val installResult = MutableStateFlow<InstallEvent?>(null)

class UpdateResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("update-session", Context.MODE_PRIVATE)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || id != prefs.getInt("session", -1)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) prefs.edit().remove("session").apply()
        installResult.value = InstallEvent(status, confirmation)
    }
}

@Suppress("DEPRECATION")
private suspend fun installApk(context: Context, update: PreparedUpdate) = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val apk = requireNotNull(pm.getPackageArchiveInfo(update.file.path, flags)) { "无法读取更新 APK" }
    val self = pm.getPackageInfo(context.packageName, flags)
    fun PackageInfo.code() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
    fun PackageInfo.signers() = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners?.toSet() else signatures?.toSet()
    require(apk.packageName == self.packageName && apk.versionName == update.release.version &&
        apk.code() == update.release.androidCode.toLong() && apk.code() > self.code() &&
        !apk.signers().isNullOrEmpty() && apk.signers() == self.signers()) { "安装包的应用、版本或签名不匹配" }
    val installer = pm.packageInstaller
    val prefs = context.getSharedPreferences("update-session", Context.MODE_PRIVATE)
    val previous = prefs.getInt("session", -1)
    if (previous >= 0) runCatching { installer.abandonSession(previous) }
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName)
        setSize(update.asset.size)
        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    }
    val id = installer.createSession(params)
    try {
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, update.asset.size).use { out ->
                update.file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateResultReceiver::class.java), flags)
            check(prefs.edit().putInt("session", id).commit())
            session.commit(callback.intentSender)
        }
    } catch (e: Exception) { runCatching { installer.abandonSession(id) }; throw e }
}

@Composable
internal fun UpdateHost(model: StudyModel) {
    val updates = model.updates ?: return
    val context = LocalContext.current
    val result by installResult.collectAsState()
    var openingPermission by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        openingPermission = false
        if (!context.packageManager.canRequestPackageInstalls()) updates.later()
    }
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_CANCELED && updates.installing) updates.finished("安装已取消，可在设置重试")
    }
    LaunchedEffect(result, updates.active) {
        val event = result ?: return@LaunchedEffect
        if (event.result == PackageInstaller.STATUS_PENDING_USER_ACTION && !updates.active) return@LaunchedEffect
        installResult.value = null
        when (event.result) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> runCatching { confirm.launch(requireNotNull(event.confirmation)) }
                .onFailure { updates.finished("系统安装界面未能打开，请重试") }
            PackageInstaller.STATUS_SUCCESS -> updates.finished("更新已完成")
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates.finished("安装已取消，可在设置重试")
            else -> updates.finished("系统未完成安装，可在设置重试（" + event.result + "）")
        }
    }
    if (!model.canInstallUpdate || !updates.autoInstall || openingPermission) return
    if (context.packageManager.canRequestPackageInstalls()) {
        LaunchedEffect(updates.prepared) { updates.begin { installApk(context.applicationContext, it) } }
    } else {
        AlertDialog(onDismissRequest = updates::later, title = { Text("允许循页安装更新？") },
            text = { Text("新版已下载。请在系统设置中允许来自循页的安装，返回后会继续更新。") },
            confirmButton = { TextButton(onClick = {
                openingPermission = true
                runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))) }
                    .onFailure { openingPermission = false; updates.finished("请在系统设置中允许循页安装应用") }
            }) { Text("打开系统设置") } },
            dismissButton = { TextButton(onClick = updates::later) { Text("稍后") } })
    }
}
