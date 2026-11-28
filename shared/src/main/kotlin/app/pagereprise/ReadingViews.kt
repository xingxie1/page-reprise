package app.pagereprise

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pagereprise.core.*

@Composable
internal fun ReadingEditor(model: StudyModel, target: ReadingPage) {
    val saved = model.editor.body.reading(target.answer, target.hash)
    var editing by remember(model.editor.draft, target) { mutableStateOf(false) }
    var needsKey by remember(model.editor.draft, target) { mutableStateOf(false) }
    val error = model.readingError(target)
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (model.recognizing(target)) Text("正在识别…", color = MaterialTheme.colorScheme.primary)
    else if (saved != null) ReadingMarkdown(saved, model.readingSize)
    Row {
        if (saved == null && !model.recognizing(target))
            TextButton(onClick = { if (model.hasReadingKey) model.recognize(target) else needsKey = true },
                enabled = !model.busy) { Text(if (error == null) "识别这张图" else "重试识别") }
        TextButton(onClick = { editing = true }, enabled = !model.busy) { Text(if (saved != null) "校对阅读稿" else "手动誊写") }
    }
    if (needsKey) ReadingKeyDialog(model, action = "保存并识别此图", saved = {
        model.setAutomaticReading(false)
        model.recognize(target)
    }) { needsKey = false }
    if (editing) {
        var text by remember { mutableStateOf(saved.orEmpty()) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(if (target.answer) "校对答案阅读稿" else "校对题干阅读稿") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(max = 320.dp), minLines = 5, label = { Text("Markdown 原文") }) },
            confirmButton = { TextButton(onClick = { if (model.correctReading(target, text)) editing = false }) { Text("应用校对") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
    }
}

private enum class ReadingMode(val label: String) { BOTH("对照"), TEXT("阅读稿"), IMAGE("原图") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingQuestion(model: StudyModel, card: Card, reveal: Boolean, widePreview: Boolean, toggle: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = widePreview || maxWidth >= 700.dp
        val hasText = card.body.readings?.let { it.questions.isNotEmpty() || it.answers.isNotEmpty() } == true
        var mode by rememberSaveable(card.id, wide) { mutableStateOf(if (wide) ReadingMode.BOTH else ReadingMode.TEXT) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasText) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val modes = if (wide) ReadingMode.entries else listOf(ReadingMode.TEXT, ReadingMode.IMAGE)
                    modes.forEach { choice -> FilterChip(mode == choice, { mode = choice }, { Text(choice.label) },
                        modifier = Modifier.testTag("read-mode-" + choice.name)) }
                    if (mode != ReadingMode.IMAGE) {
                        TextButton(onClick = { model.resizeReading(-2) }, enabled = model.readingSize > 15) { Text("小字") }
                        TextButton(onClick = { model.resizeReading(2) }, enabled = model.readingSize < 25) { Text("大字") }
                    }
                }
            }
            val selected = if (hasText) mode else ReadingMode.IMAGE
            ReadingSide(model, card, false, selected)
            OutlinedButton(onClick = toggle, modifier = Modifier.fillMaxWidth()) { Text(if (reveal) "收起答案与错因" else "核对答案与错因") }
            if (reveal) ReadingSide(model, card, true, selected)
        }
    }
}

@Composable
private fun ReadingSide(model: StudyModel, card: Card, answer: Boolean, mode: ReadingMode) {
    val body = card.body
    val images = body.pages(answer)
    val label = if (answer) "参考答案" else "题干图，点按放大"
    Sheet(Modifier.fillMaxWidth()) {
        Text(if (answer) "答案与错因" else "题干", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (!answer) Text(card.label, style = MaterialTheme.typography.titleMedium)
        else if (body.solution.isNotBlank()) Text(body.solution)
        if (mode == ReadingMode.BOTH && images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("原图", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("阅读稿", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        }
        images.forEach { hash ->
            val text = body.reading(answer, hash)
            when (mode) {
                ReadingMode.BOTH -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { Photo(model, hash, label) }
                    Box(Modifier.weight(1f)) {
                        if (text == null) Text("此图尚无阅读稿", style = MaterialTheme.typography.bodySmall)
                        else ReadingMarkdown(text, model.readingSize)
                    }
                }
                ReadingMode.TEXT -> if (text == null) Photo(model, hash, label) else ReadingMarkdown(text, model.readingSize)
                ReadingMode.IMAGE -> Photo(model, hash, label)
            }
        }
        if (!answer && body.source.isNotBlank()) Text(body.source, style = MaterialTheme.typography.bodySmall)
        if (answer && images.isEmpty() && body.solution.isBlank()) Text("尚未补充答案，可对照原稿自行判断。")
    }
}
