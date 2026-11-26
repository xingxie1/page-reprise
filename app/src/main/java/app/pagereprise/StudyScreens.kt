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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pagereprise.core.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorScreen(model: StudyModel) {
    val editor = model.editor
    var answerPick by rememberSaveable { mutableStateOf(false) }
    var crop by remember { mutableStateOf<Pair<Boolean,Int>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> model.importImages(uris, answerPick) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(if (editor.card == null) "录入一道题" else "编辑原题", "先保存干净的题面，答案和来源可以以后补。")
        if (editor.card == null) Sheet(Modifier.fillMaxWidth()) {
            Text("放进哪一科？", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                model.selectable.forEach { key -> FilterChip(editor.subject == key, { model.selectSubject(key) }, { Text(Subjects.name(key)) }) }
            }
            Text(if (editor.subject.isBlank()) "未选科目 · 只能暂存草稿" else Subjects.name(editor.subject) + " · 普通错题本", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(editor.body.title, { model.change(model.editor.body.copy(title = it)) },
            Modifier.fillMaxWidth().testTag("question-text"), label = { Text("题干文字（有题图可留空）") }, minLines = 2)
        for (answers in listOf(false, true)) {
            val images = if (answers) editor.body.answers else editor.body.pictures
            Sheet(Modifier.fillMaxWidth()) {
                Text(if (answers) "答案图 · 复习时折叠" else "题干图 · 按顺序展示", fontWeight = FontWeight.Bold)
                images.forEachIndexed { index, hash ->
                    Photo(model, hash, "第 " + (index + 1) + " 张")
                    FlowRow {
                        TextButton(onClick = { crop = answers to index }) { Text("裁切") }
                        TextButton(enabled = index > 0, onClick = {
                            val updated = images.toMutableList().apply { add(index - 1, removeAt(index)) }
                            model.change(if (answers) editor.body.copy(answers = updated) else editor.body.copy(pictures = updated))
                        }) { Text("上移") }
                        TextButton(enabled = !model.busy && (if (answers) editor.body.pictures else editor.body.answers).size < 16,
                            onClick = { model.movePhoto(answers, index) }) { Text(if (answers) "移到题干" else "移到答案") }
                        TextButton(onClick = {
                            val updated = images.toMutableList().apply { removeAt(index) }
                            model.change(if (answers) editor.body.copy(answers = updated) else editor.body.copy(pictures = updated))
                        }) { Text("移除") }
                    }
                }
                OutlinedButton(onClick = { answerPick = answers; picker.launch(arrayOf("image/*")) }, enabled = !model.busy && images.size < 16) { Text(if (answers) "＋ 添加答案图" else "＋ 添加题干图") }
            }
        }
        OutlinedTextField(editor.body.solution, { model.change(model.editor.body.copy(solution = it)) }, Modifier.fillMaxWidth(), label = { Text("解法或一句错因（选填）") }, minLines = 2)
        if (editor.subject == "cs") {
            Text("408 考点（可多选，不改变所属题本）")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Subjects.parts.values.forEach { tag ->
                    val active = tag in editor.body.tags
                    FilterChip(active, {
                        model.change(editor.body.copy(tags = if (active) editor.body.tags - tag else editor.body.tags + tag))
                    }, { Text(tag) })
                }
            }
        }
        OutlinedTextField(editor.body.source, { model.change(model.editor.body.copy(source = it)) }, Modifier.fillMaxWidth(), label = { Text("题号、页码或考点（选填）") })
        Button(onClick = model::saveCard, enabled = !model.busy && editor.subject.isNotBlank() && (editor.body.title.isNotBlank() || editor.body.pictures.isNotEmpty()), modifier = Modifier.fillMaxWidth().testTag("save-card")) {
            Text(if (editor.card == null) "保存到" + Subjects.name(editor.subject) + "普通本" else "保存改动，不改变排期")
        }
        if (editor.card == null) OutlinedButton(onClick = model::saveDraft, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("仅存为本机草稿") }
    }
    crop?.let { (answer,index) ->
        val hash = (if (answer) editor.body.answers else editor.body.pictures).getOrNull(index)
        if (hash != null) CropDialog(model,hash,{ crop = null }) { bounds -> crop = null; model.cropPhoto(answer,index,bounds) }
    }
}

@Composable
private fun Question(model: StudyModel, card: Card, reveal: Boolean, toggle: () -> Unit) {
    Sheet(Modifier.fillMaxWidth()) {
        Text("题干", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (card.body.title.isNotBlank()) Text(card.body.title, style = MaterialTheme.typography.titleMedium)
        card.body.pictures.forEach { Photo(model, it, "题干图，点按放大") }
        if (card.body.source.isNotEmpty()) Text(card.body.source, style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = toggle, modifier = Modifier.fillMaxWidth()) { Text(if (reveal) "收起答案与错因" else "核对答案与错因") }
    if (reveal) Sheet(Modifier.fillMaxWidth()) {
        Text("答案与错因", fontWeight = FontWeight.Bold)
        if (card.body.solution.isNotBlank()) Text(card.body.solution)
        card.body.answers.forEach { Photo(model, it, "参考答案") }
        if (card.body.solution.isBlank() && card.body.answers.isEmpty()) Text("尚未补充答案，可对照原稿自行判断。")
    }
}

@Composable
internal fun Review(model: StudyModel) {
    val turn = model.queue.firstOrNull() ?: return
    val card = model.cards.firstOrNull { it.id == turn.card }
    var reveal by rememberSaveable(turn.card, turn.round) { mutableStateOf(false) }
    var promote by remember(turn) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(if (model.manual) "手动抽练" else "到期复习", "本次剩余 " + model.queue.size + " 题" + if (model.manual) " · 不改变排期" else " · 猜对也算没做对")
        if (card == null || card.subject != model.subject || card.plan.focus != model.focus || card.conflict ||
            (!model.manual && (card.round != turn.round || !card.due(model.today)))) {
            Text("这一轮已在其他设备处理或需要核对，不再重复计分。")
            Button(onClick = model::next) { Text("继续") }
        } else {
            Question(model, card, reveal) { reveal = !reveal }
            if (model.manual) Button(onClick = model::next, modifier = Modifier.fillMaxWidth()) { Text("下一题 · 不计分") }
            else {
                val allCached = remember(card.body.pictures, model.owner, model.mediaRevision) { card.body.pictures.all { model.media.file(it).isFile } }
                if (!allCached) Text("题干尚未完整下载，联网同步后再判分。")
                Button(onClick = { model.grade(card, true, false) }, enabled = !model.busy && allCached, modifier = Modifier.fillMaxWidth()) { Text("独立做对了") }
                OutlinedButton(onClick = { if (card.plan.resets == 1) promote = true else model.grade(card, false, false) },
                    enabled = !model.busy && allCached, modifier = Modifier.fillMaxWidth()) { Text("没做对（含猜对）") }
                TextButton(onClick = model::next, enabled = !model.busy) { Text("稍后再做 · 保留待办") }
            }
        }
    }
    if (promote && card != null) Confirm("第二次清零", "原题将移到「" + Subjects.name(card.subject) + " · 永久重点本」，明天重开。取消不会记分。",
        "确认移入重点本", { promote = false }) { promote = false; model.grade(card, false, true) }
}

@Composable
internal fun Confirm(title: String, description: String, action: String, cancel: () -> Unit, ok: () -> Unit) {
    AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = { Text(description) },
        confirmButton = { TextButton(onClick = ok) { Text(action) } }, dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Details(model: StudyModel, card: Card) {
    var reveal by rememberSaveable(card.id) { mutableStateOf(false) }
    var moving by remember(card.id) { mutableStateOf(false) }
    var target by remember(card.id) { mutableStateOf("") }
    var undo by remember(card.id) { mutableStateOf(false) }
    var confirmChoice by remember(card.id) { mutableStateOf<Event?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("题目详情", status(card))
        if (card.conflict) Sheet(Modifier.fillMaxWidth()) {
            Heading("请核对双端记录", "请选择保留的结果；核对前该题不计入待办。")
            val options = when { card.moveConflict -> card.moves; card.editConflict -> card.edits; else -> card.votes }
            options.forEach { option ->
                val label = when(option.kind) {
                    Kind.MOVE -> "归入 " + Subjects.name(option.value)
                    Kind.EDIT -> option.body?.title.orEmpty().ifBlank { "图片题面" } + " · " + option.body?.source.orEmpty()
                    Kind.UNDO -> "撤销这一轮"
                    else -> if (option.value == "right") "独立做对" else "没做对（含猜对）"
                }
                if (option.kind == Kind.EDIT) {
                    Text(option.body?.solution.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    (option.body?.pictures.orEmpty() + option.body?.answers.orEmpty()).forEach { Photo(model, it, "待核对版本") }
                }
                OutlinedButton(onClick = {
                    if (option.kind == Kind.REVIEW && option.value == "wrong" && card.plan.resets == 1) confirmChoice = option
                    else model.resolve(card, option)
                }, enabled = !model.busy) { Text(StudyDays.date(option.at).toString() + " · " + label.take(80)) }
            }
        }
        Question(model, card, reveal) { reveal = !reveal }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { model.detail(card); model.editCard(card) }, enabled = !card.conflict) { Text("编辑题目") }
            TextButton(onClick = { moving = !moving }, enabled = !card.conflict) { Text("更正科目") }
            if (card.canUndo) TextButton(onClick = { undo = true }) { Text("撤销最近一轮") }
        }
        if (moving) FlowRow { model.selectable.filterNot { it == card.subject }.forEach { key ->
            TextButton(onClick = { target = key }) { Text(Subjects.name(key)) }
        } }
        Heading("复习记录", "实际提交日 · 共 " + card.history.size + " 轮")
        card.history.asReversed().forEach { step ->
            val vote = step.votes.first()
            Text(StudyDays.date(vote.at).toString() + "    " + (if (vote.value == "right") "做对" else "没做对") +
                "    " + (step.after.due?.let { "下次 " + it } ?: "已掌握"))
        }
    }
    if (target.isNotBlank()) Confirm("更正科目", Subjects.name(card.subject) + " → " + Subjects.name(target) + "。本别、日期和历史不变。", "移动原题", { target = "" }) {
        model.move(card, target); target = ""; moving = false
    }
    if (undo) Confirm("撤销最近作答？", "恢复该轮开始前的进度和日期。已确认的永久升级不可快捷撤销。", "撤销", { undo = false }) {
        undo = false; model.undo(card)
    }
    confirmChoice?.let { chosen ->
        Confirm("确认第二次清零？", "保留这次结果将把原题移入同科永久重点本。", "确认移入重点本", { confirmChoice = null }) {
            confirmChoice = null; model.resolve(card, chosen)
        }
    }
}
