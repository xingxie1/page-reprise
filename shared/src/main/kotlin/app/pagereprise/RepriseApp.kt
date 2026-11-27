package app.pagereprise

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pagereprise.core.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RepriseApp(model: StudyModel, platformDialogs: @Composable () -> Unit = {}) {
    MaterialTheme(colorScheme = graphiteColors) {
        val colors = MaterialTheme.colorScheme
        val todayList = key(model.owner) { rememberLazyListState() }
        val snacks = remember { SnackbarHostState() }
        var leave by remember { mutableStateOf(false) }
        val navigateBack: () -> Unit = {
            if (!model.busy && model.page == Page.EDITOR && model.editor.body != Body()) leave = true else model.back()
        }
        PlatformBackHandler(model.page != Page.HOME && model.viewedPhoto == null) { navigateBack() }
        LaunchedEffect(model.message) {
            model.message?.let { text -> snacks.currentSnackbarData?.dismiss(); snacks.showSnackbar(text); if (model.message == text) model.message = null }
        }
        Box(Modifier.fillMaxSize()) {
            val background = if (model.viewedPhoto == null) Modifier else Modifier.clearAndSetSemantics { }
            Scaffold(modifier = background, containerColor = colors.background, contentColor = colors.onBackground, snackbarHost = { SnackbarHost(snacks) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    val headerSwipe = if (model.page == Page.BOOK) Modifier.onNotebookSwipe { model::stepBook } else Modifier
                    Row(Modifier.fillMaxWidth().height(56.dp).then(headerSwipe).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (model.page != Page.HOME) TextButton(onClick = navigateBack, enabled = !model.busy) { Text("‹ 返回") }
                        else {
                            Image(brandIcon(), contentDescription = null, modifier = Modifier.size(28.dp)
                                .clip(RoundedCornerShape(23)).background(launcherBackground))
                            Spacer(Modifier.width(8.dp))
                            Text("循页", fontWeight = FontWeight.Black, fontSize = 24.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(if (model.page == Page.HOME) "PAGE REPRISE" else when(model.page) {
                            Page.EDITOR -> "录题工作台"; Page.SETTINGS -> "账号与数据"; Page.ISSUES -> "待核对"
                            Page.TODAY -> "今日复习"
                            Page.TRASH -> Subjects.name(model.subject) + " · 回收站"
                            else -> Subjects.name(model.subject) + if (model.focus) " · 永久重点本" else " · 普通错题本"
                        }, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        if (model.page == Page.HOME) TextButton(onClick = model::settings) { Text("设置") }
                    }
                    if (model.updates?.downloading == true) Text(model.updates.status, Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall)
                    if (model.busy || model.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (model.page) {
                            Page.HOME -> Home(model)
                            Page.TODAY -> TodayScreen(model, todayList)
                            Page.BOOK -> Book(model)
                            Page.REVIEW -> Review(model)
                            Page.DETAIL -> {
                                val card = model.cards.firstOrNull { it.id == model.selected }
                                if (card != null) Details(model, card)
                                else if (model.ready) LaunchedEffect(model.selected) { model.back() }
                            }
                            Page.EDITOR -> EditorScreen(model)
                            Page.SETTINGS -> Settings(model)
                            Page.ISSUES -> IssueBooks(model)
                            Page.TRASH -> TrashScreen(model)
                        }
                    }
                }
            }
            model.viewedPhoto?.let { photo -> FullScreenPhoto(model, photo) }
        }
        UpdateHost(model)
        if (model.updates?.installing == true) AlertDialog(onDismissRequest = {}, title = { Text("正在安装更新") },
            text = { Text("请等待系统完成安装。") }, confirmButton = {})
        if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("离开当前编辑？") },
            text = { Text(if (model.editor.card == null) "可以暂存为本机草稿，之后再选科、补图。" else "未保存的改动不会写入题目。") },
            confirmButton = { TextButton(onClick = { leave = false; if (model.editor.card == null) model.saveDraft() else model.saveCard() }) { Text(if (model.editor.card == null) "暂存离开" else "保存离开") } },
            dismissButton = { TextButton(onClick = { leave = false; model.back() }) { Text("放弃改动") } })
        platformDialogs()
    }
}

@Composable
internal fun Heading(title: String, detail: String = "") {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Sheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = colors.surface, contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Home(model: StudyModel) {
    val colors = MaterialTheme.colorScheme
    val due = model.dueCards
    val unknown = model.waitingForLibrary
    val next = model.cards.mapNotNull { it.plan.due }.filter { it.isAfter(model.today) }.minOrNull()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 640.dp) 2 else 1
        LazyColumn(Modifier.fillMaxSize().testTag("home-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(onClick = model::openToday, modifier = Modifier.testTag("open-today"), color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer, shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(model.today.format(DateTimeFormatter.ofPattern("MM月dd日 · EEEE", Locale.CHINA)), color = colors.secondary, style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("今日复习", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("查看全部 ›", style = MaterialTheme.typography.labelLarge)
                        }
                        Text(if (unknown) "正在等待题库数据" else due.size.toString() + " 题待复习", fontSize = 28.sp,
                            color = if (unknown) colors.onPrimaryContainer else colors.primary)
                        Text(if (unknown) (model.syncText.ifBlank { "首次同步完成前不统计待办。" })
                            else if (due.isNotEmpty()) "其中逾期 " + due.count { it.plan.due!!.isBefore(model.today) } + " 题"
                            else next?.let { "今天已经清空，下次复习 " + it } ?: "从一道值得再做的题开始。", color = colors.secondary)
                    }
                }
            }
            item { Button(onClick = { model.newEditor() }, modifier = Modifier.fillMaxWidth().testTag("new-card")) { Text("＋  录入一道题") } }
            item { Heading("我的题本") }
            items(model.subjects.chunked(columns)) { keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    keys.forEach { key ->
                        val own = model.cards.filter { it.subject == key }
                        Sheet(Modifier.weight(1f).testTag("subject:" + key).clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button, onClickLabel = "打开" + Subjects.name(key) + "题本") { model.openBook(key, false) }) {
                            Text(Subjects.name(key), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (focus in listOf(false, true)) {
                                    val count = own.count { it.plan.focus == focus && it.due(model.today) }
                                    OutlinedButton(onClick = { model.openBook(key, focus) }, modifier = Modifier.weight(1f).testTag("book:" + key + ":" + focus),
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.surfaceVariant,
                                            contentColor = if (focus) colors.primary else colors.onSurface),
                                        border = BorderStroke(1.dp, if (focus) colors.outline else colors.outlineVariant),
                                        contentPadding = PaddingValues(4.dp)) {
                                        Text((if (focus) "重点  " else "普通  ") + if (unknown) "—" else count.toString())
                                    }
                                }
                            }
                        }
                    }
                    if (keys.size < columns) Spacer(Modifier.weight(1f))
                }
            }
            if (model.cards.any { it.conflict }) item { OutlinedButton(onClick = model::issues) { Text("核对双端记录 · " + model.cards.count { it.conflict } + " 题") } }
            if (model.drafts.isNotEmpty()) item { Heading("本机草稿", "未入本，不排期，不上传。") }
            items(model.drafts, key = { it.draftId }) { draft ->
                val body = remember(draft.bodyJson) { Codec.json.fromJson(draft.bodyJson, Body::class.java) }
                TextButton(onClick = { model.draft(draft) }) { Text("继续录入  ·  " + body.title.ifBlank { "图片草稿" }.take(40)) }
            }
            item { Text(if (model.session == null) "本机试用 · 登录后可选择迁入自己的云端题库。" else model.syncText.ifBlank { "上次同步：" + model.lastSync.takeIf { it > 0 }?.let(StudyDays::date) },
                style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TodayScreen(model: StudyModel, list: LazyListState) {
    val due = model.dueCards
    LazyColumn(Modifier.fillMaxSize().testTag("today-list"), state = list,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Heading("今天要复习的题", if (model.waitingForLibrary) "正在等候题库数据" else "共 " + due.size + " 题 · 按录入时间从早到晚")
        }
        if (due.isEmpty() && !model.waitingForLibrary) item { Text("今天的复习已完成") }
        items(due, key = { it.id }) { card ->
            Sheet(Modifier.fillMaxWidth().testTag("today-" + card.id).clickable { model.reviewToday(card) }) {
                Text(card.label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(Subjects.name(card.subject) + (if (card.plan.focus) " · 永久重点" else " · 普通") +
                    " · " + card.plan.due + (if (card.plan.due!!.isBefore(model.today)) " 到期（逾期）" else " 到期"),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun IssueBooks(model: StudyModel) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("选择要核对的本", "两台设备的判分不同时，不会自动算作两轮。")
        model.cards.filter { it.conflict }.groupBy { it.subject to it.plan.focus }.forEach { (scope, rows) ->
            OutlinedButton(onClick = { model.openBook(scope.first, scope.second) }) {
                Text(Subjects.name(scope.first) + (if (scope.second) " · 重点" else " · 普通") + " · " + rows.size + " 题")
            }
        }
    }
}

internal fun status(card: Card): String = if (card.conflict) "需要核对 · 仍只保留一道题"
    else (card.plan.due?.let { "下次 " + it } ?: "已掌握 · 不再排期") + "  /  连对 " + card.plan.streak + "  /  清零 " + card.plan.resets
