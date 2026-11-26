package app.pagereprise

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pagereprise.core.*
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: StudyModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RepriseApp(model) }
        if (savedInstanceState == null) handle(intent)
    }
    override fun onResume() { super.onResume(); model.foreground() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }
    @Suppress("DEPRECATION")
    private fun handle(intent: Intent?) {
        if (intent?.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        intent ?: return
        val photos = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(photos::add)
        else photos += intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        intent.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(photos::add) }
        model.shared(photos.distinct().filter { it.scheme == "content" })
    }
}

private val Pine = Color(0xFF164E43)
private val Cream = Color(0xFFF8F7F1)
private val Ink = Color(0xFF182D28)
private val Amber = Color(0xFF97532E)
private val colors = lightColorScheme(primary = Pine, onPrimary = Color.White, secondary = Amber, background = Cream,
    surface = Color.White, onSurface = Ink, onBackground = Ink, primaryContainer = Color(0xFFDDEDE5),
    onPrimaryContainer = Pine, surfaceVariant = Color(0xFFE9EAE1), outline = Color(0xFF79857C))

@Composable
fun RepriseApp(model: StudyModel) {
    MaterialTheme(colorScheme = colors) {
        val snacks = remember { SnackbarHostState() }
        var leave by remember { mutableStateOf(false) }
        val navigateBack: () -> Unit = {
            if (!model.busy && model.page == Page.EDITOR && model.editor.body != Body()) leave = true else model.back()
        }
        BackHandler(model.page != Page.HOME) { navigateBack() }
        LaunchedEffect(model.message) {
            model.message?.let { text -> snacks.currentSnackbarData?.dismiss(); snacks.showSnackbar(text); if (model.message == text) model.message = null }
        }
        LaunchedEffect(Unit) {
            while (true) {
                model.today = StudyDays.today()
                val nextMidnight = model.today.plusDays(1).atStartOfDay(StudyDays.zone).toInstant().toEpochMilli()
                delay((nextMidnight - System.currentTimeMillis()).coerceIn(30L, 60000L))
            }
        }
        Scaffold(containerColor = Cream, snackbarHost = { SnackbarHost(snacks) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (model.page != Page.HOME) TextButton(onClick = navigateBack, enabled = !model.busy) { Text("‹ 返回") }
                    else Text("循页", fontWeight = FontWeight.Black, fontSize = 24.sp, color = Pine)
                    Spacer(Modifier.weight(1f))
                    Text(if (model.page == Page.HOME) "PAGE REPRISE" else when(model.page) {
                        Page.EDITOR -> "录题工作台"; Page.SETTINGS -> "账号与数据"; Page.ISSUES -> "待核对"
                        else -> Subjects.name(model.subject) + if (model.focus) " · 永久重点本" else " · 普通错题本"
                    }, style = MaterialTheme.typography.labelMedium, color = Pine)
                    if (model.page == Page.HOME) TextButton(onClick = model::settings) { Text("设置") }
                }
                if (model.busy || model.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (model.page) {
                        Page.HOME -> Home(model)
                        Page.BOOK -> Book(model)
                        Page.REVIEW -> Review(model)
                        Page.DETAIL -> model.cards.firstOrNull { it.id == model.selected }?.let { Details(model, it) }
                        Page.EDITOR -> EditorScreen(model)
                        Page.SETTINGS -> Settings(model)
                        Page.ISSUES -> IssueBooks(model)
                    }
                }
            }
        }
        if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("离开当前编辑？") },
            text = { Text(if (model.editor.card == null) "可以暂存为本机草稿，之后再选科、补图。" else "未保存的改动不会写入题目。") },
            confirmButton = { TextButton(onClick = { leave = false; if (model.editor.card == null) model.saveDraft() else model.saveCard() }) { Text(if (model.editor.card == null) "暂存离开" else "保存离开") } },
            dismissButton = { TextButton(onClick = { leave = false; model.back() }) { Text("放弃改动") } })
    }
}

@Composable
internal fun Heading(title: String, detail: String = "") {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Sheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFDFE5DC))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Home(model: StudyModel) {
    val due = model.cards.filter { it.due(model.today) }
    val unknown = !model.ready || (model.session != null && model.lastSync == 0L && model.cards.isEmpty())
    val next = model.cards.mapNotNull { it.plan.due }.filter { it.isAfter(model.today) }.minOrNull()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 640.dp) 2 else 1
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(color = Pine, contentColor = Color.White, shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(model.today.format(DateTimeFormatter.ofPattern("MM月dd日 · EEEE", Locale.CHINA)), color = Color(0xFFC8DDCE), style = MaterialTheme.typography.labelLarge)
                        Text("今日复习", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(if (unknown) "正在等待题库数据" else due.size.toString() + " 题待复习", fontSize = 28.sp)
                        Text(if (unknown) (model.syncText.ifBlank { "首次同步完成前不统计待办。" })
                            else if (due.isNotEmpty()) "其中逾期 " + due.count { it.plan.due!!.isBefore(model.today) } + " 题 · 选一本开始"
                            else next?.let { "今天已经清空，下次复习 " + it } ?: "从一道值得再做的题开始。", color = Color(0xFFDFEDE2))
                    }
                }
            }
            item { Button(onClick = { model.newEditor() }, modifier = Modifier.fillMaxWidth().testTag("new-card")) { Text("＋  录入一道题") } }
            item { Heading("我的题本", "普通与永久重点分开复习，每道题只属于一本。") }
            items(model.subjects.chunked(columns)) { keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    keys.forEach { key ->
                        val own = model.cards.filter { it.subject == key }
                        Sheet(Modifier.weight(1f)) {
                            Text(Subjects.name(key), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (focus in listOf(false, true)) {
                                    val count = own.count { it.plan.focus == focus && it.due(model.today) }
                                    OutlinedButton(onClick = { model.openBook(key, focus) }, modifier = Modifier.weight(1f).testTag("book:" + key + ":" + focus), contentPadding = PaddingValues(4.dp)) {
                                        Text((if (focus) "重点  " else "普通  ") + if (unknown) "—" else count.toString(), color = if (focus) Amber else Pine)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Book(model: StudyModel) {
    val book = model.book
    var query by rememberSaveable(model.subject, model.focus) { mutableStateOf("") }
    var filter by rememberSaveable(model.subject, model.focus) { mutableStateOf("全部") }
    var peek by rememberSaveable(model.subject, model.focus) { mutableStateOf("") }
    val items = book.filter { (query.isBlank() || (it.body.title + " " + it.body.source + " " + it.body.tags.joinToString(" ")).contains(query, true)) && when(filter) {
        "已到期" -> it.due(model.today); "已掌握" -> it.plan.due == null; "待核对" -> it.conflict; else -> true
    } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Heading(if (model.focus) "永久重点本" else "普通错题本", Subjects.name(model.subject) + " · 共 " + book.size + " 题") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { model.start(false) }, enabled = book.any { it.due(model.today) }) { Text("开始到期复习") }
                        OutlinedButton(onClick = { model.start(true, items) }, enabled = items.any { !it.conflict }) { Text("手动抽练") }
                        TextButton(onClick = { model.newEditor(model.subject) }) { Text("＋ 录题") }
                    }
                }
                item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("只搜索这一本") }) }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("全部", "已到期", "已掌握", "待核对").forEach { key -> FilterChip(filter == key, { filter = key }, { Text(key) }) }
                } }
                if (items.isEmpty()) item { Sheet { Heading("这里还没有题", "可以调整筛选，或录入第一道题。") } }
                items(items, key = { it.id }) { card ->
                    Sheet(Modifier.fillMaxWidth().clickable { if (wide) peek = card.id else model.detail(card) }) {
                        Text(card.body.title.ifBlank { "图片题 · " + card.body.pictures.size + " 张" }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(status(card), style = MaterialTheme.typography.bodySmall, color = if (card.conflict) Amber else Pine)
                    }
                }
            }
            if (wide) {
                VerticalDivider()
                Box(Modifier.weight(1.15f)) { items.firstOrNull { it.id == peek }?.let { Details(model, it) } ?: Text("选一道题，在这里预览。", Modifier.padding(24.dp)) }
            }
        }
    }
}

internal fun status(card: Card): String = if (card.conflict) "需要核对 · 仍只保留一道题"
    else (card.plan.due?.let { "下次 " + it } ?: "已掌握 · 不再排期") + "  /  连对 " + card.plan.streak + "  /  清零 " + card.plan.resets
