package app.pagereprise

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pagereprise.core.*

@Composable
internal fun Settings(model: StudyModel) {
    var endpoint by rememberSaveable { mutableStateOf(model.serviceAddress) }
    var key by remember { mutableStateOf(model.publishableKey) }
    var email by rememberSaveable { mutableStateOf(model.session?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var transfer by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(model::export) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::stage) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("账号与数据", "数据只在自己的账号和设备间流转。")
        Sheet(Modifier.fillMaxWidth()) {
            val auth = model.session
            if (auth == null) {
                Heading("本机试用", "可以先录题。换设备前，请登录并将试用题目迁入自己的账号。")
                TextButton(onClick = { showLogin = !showLogin }) { Text("登录受邀账号") }
            } else {
                Heading(auth.email, "待上传操作：" + model.pending + " · " + model.syncText)
                Text(if (model.lastSync > 0) "最近完整同步：" + java.time.Instant.ofEpochMilli(model.lastSync).atZone(StudyDays.zone).toLocalDateTime().withNano(0)
                    else "首次同步尚未全部完成。")
                Button(onClick = model::sync, enabled = !model.syncing) { Text("立即同步 / 重试") }
                OutlinedButton(onClick = { transfer = true }, enabled = !model.busy) { Text("把本机试用题目复制到此账号") }
                TextButton(onClick = { logout = true }, enabled = !model.syncing && !model.busy) { Text("退出此账号") }
            }
            if (auth == null && showLogin) {
                Text("服务地址和公开密钥由邀请你的人提供。无需百度或夸克密码。")
                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Supabase HTTPS 服务地址") })
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Publishable key / anon key") })
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("邮箱") })
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("密码（首次激活时自行设置）") })
                OutlinedTextField(invite, { invite = it }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("首次激活的邮件邀请码（已激活留空）") })
                Button(onClick = { model.login(endpoint, key, email, password, invite) }, enabled = !model.busy && endpoint.isNotBlank() && key.isNotBlank() && email.isNotBlank() && password.isNotBlank()) {
                    Text(if (invite.isBlank()) "登录" else "激活并登录")
                }
            }
        }
        Sheet(Modifier.fillMaxWidth()) {
            Heading("408 学科设置")
            Row { Text("将四门分别作为可选科目", Modifier.weight(1f)); Switch(model.split, { model.toggleSplit() }) }
            Text("不会复制或移动旧题。原 408 本有题时仍可打开，需本人逐题更正归属。", style = MaterialTheme.typography.bodySmall)
        }
        Sheet(Modifier.fillMaxWidth()) {
            Heading("整库备份", "包含正式题图、各科归属和作答历史；不含密码与本机草稿。")
            if (model.session == null) Text("试用备份绑定本次安装。换机请先迁入受邀账号，账号备份可跨设备恢复。")
            OutlinedButton(onClick = { exporter.launch("循页-" + model.today + ".zip") }, enabled = !model.busy) { Text("导出备份包") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !model.busy) { Text("选择备份并预览") }
            Text("导出后可手动上传至夸克网盘；不要用公开分享链接发送含私有笔记的备份。", style = MaterialTheme.typography.bodySmall)
        }
        Sheet(Modifier.fillMaxWidth()) {
            Heading("复习规则")
            Text("尚未清零：1 → 7 → 14 天\n已经清零：1 → 3 → 14 天\n按北京时间自然日计算。猜中算错，第二次清零才升级。")
        }
    }
    model.preview?.let { preview ->
        val summary = remember(preview) {
            Library.project(preview.index.events).groupBy { it.subject }.map { (key, cards) ->
                Subjects.name(key) + "：普通 " + cards.count { !it.plan.focus } + "，重点 " + cards.count { it.plan.focus }
            }.joinToString("\n").ifBlank { "没有正式题目" }
        }
        Confirm("合并此备份？", "账号：" + (model.session?.email ?: "本机试用") + "\n备份日：" + StudyDays.date(preview.index.savedAt) + "\n" + summary, "确认合并", model::restoreCancel, model::restore)
    }
    if (transfer) Confirm("迁入本机试用题库？", "仅复制正式题目及附件到当前账号，之后会上传云端。原试用记录保留；重复操作不会增题。", "确认复制", { transfer = false }) {
        transfer = false; model.importGuest()
    }
    if (logout) Confirm("退出账号？", "本设备缓存仍按账号隔离；未上传的记录需再次登录同一账号后同步。", "退出", { logout = false }) {
        logout = false; model.logout()
    }
}
