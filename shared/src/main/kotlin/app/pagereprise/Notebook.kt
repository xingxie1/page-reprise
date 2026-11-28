package app.pagereprise

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pagereprise.core.Card
import app.pagereprise.core.Subjects
import java.time.LocalDate

private enum class BookFilter(val label: String) {
    ALL("全部"), DUE("已到期"), MASTERED("已掌握"), CONFLICT("待核对")
}

private data class NotebookPage(
    val focus: Boolean, val filter: BookFilter, val query: String,
    val book: List<Card>, val today: LocalDate, val list: LazyListState
) {
    val index = (if (focus) BookFilter.entries.size else 0) + filter.ordinal
    val rows = book.filter { card ->
        (query.isBlank() || (card.label + " " + card.body.source + " " + card.body.tags.joinToString(" ")).contains(query, true)) &&
            when (filter) {
                BookFilter.ALL -> true
                BookFilter.DUE -> card.due(today)
                BookFilter.MASTERED -> card.plan.due == null
                BookFilter.CONFLICT -> card.conflict
            }
    }
}

internal fun StudyModel.stepBook(step: Int) = openBook(subject, step > 0)

private fun LazyListState.actionAt(y: Float, book: (Int) -> Unit, filter: (Int) -> Unit): ((Int) -> Unit)? {
    val visible = layoutInfo.visibleItemsInfo
    val search = visible.firstOrNull { it.key == "book-search" }
        ?: return if (visible.any { it.key == "book-heading" || it.key == "book-actions" }) book else filter
    val top = search.offset - layoutInfo.viewportStartOffset
    return when {
        y < top -> book
        y >= top + search.size -> filter
        else -> null
    }
}

@Composable
internal fun Book(model: StudyModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BookPages(model, wide = maxWidth >= 760.dp)
    }
}

@Composable
private fun BookPages(model: StudyModel, wide: Boolean) {
    var query by rememberSaveable(model.subject, model.focus) { mutableStateOf("") }
    var filter by rememberSaveable(model.subject, model.focus) { mutableStateOf(BookFilter.ALL) }
    var peek by rememberSaveable(model.subject, model.focus) { mutableStateOf("") }
    val list = key(model.subject, model.focus, filter) { rememberLazyListState() }
    val page = remember(model.cards, model.today, model.subject, model.focus, filter, query, list) {
        NotebookPage(model.focus, filter, query, model.book, model.today, list)
    }
    val stepFilter: (Int) -> Unit = { step ->
        BookFilter.entries.getOrNull(filter.ordinal + step)?.let { filter = it }
    }
    Row(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = page,
            contentKey = { it.index },
            modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .onNotebookSwipe { y -> list.actionAt(y, model::stepBook, stepFilter) },
            transitionSpec = {
                val direction = if (targetState.index > initialState.index) SlideDirection.Left else SlideDirection.Right
                slideIntoContainer(direction, tween(240)) togetherWith slideOutOfContainer(direction, tween(240))
            },
            label = "题本翻页"
        ) { shown ->
            // The outgoing list stays visible for a few frames. Only the destination accepts item actions.
            val input = if (shown.index == page.index) Modifier.testTag("book-list") else
                Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            NotebookList(model, shown, input, onSearch = { query = it }, onFilter = { filter = it }) { card ->
                if (wide) peek = card.id else model.detail(card)
            }
        }
        if (wide) {
            VerticalDivider()
            Box(Modifier.weight(1.15f)) {
                page.rows.firstOrNull { it.id == peek }?.let { Details(model, it, widePreview = true) }
                    ?: Text("选一道题，在这里预览。", Modifier.padding(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotebookList(model: StudyModel, page: NotebookPage, modifier: Modifier,
                         onSearch: (String) -> Unit, onFilter: (BookFilter) -> Unit, onCard: (Card) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), state = page.list, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "book-heading") {
            FlowRow(Modifier.fillMaxWidth().testTag("book-switcher"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (focus in listOf(false, true)) {
                    FilterChip(selected = page.focus == focus, onClick = { model.openBook(model.subject, focus) },
                        label = { Text(if (focus) "重点错题本" else "普通错题本") }, modifier = Modifier.testTag("choose-book:" + focus))
                }
            }
            Text(Subjects.name(model.subject) + " · 共 " + page.book.size + " 题",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "book-actions") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { model.start(false) }, enabled = page.book.any { it.due(page.today) }) { Text("开始到期复习") }
                OutlinedButton(onClick = { model.start(true, page.rows) }, enabled = page.rows.any { !it.conflict }) { Text("手动抽练") }
                TextButton(onClick = { model.newEditor(model.subject) }) { Text("＋ 录题") }
                if (!page.focus) TextButton(onClick = model::openTrash) { Text("回收站") }
            }
        }
        item(key = "book-search") {
            OutlinedTextField(page.query, onSearch, Modifier.fillMaxWidth().testTag("book-search"), singleLine = true,
                label = { Text("只搜索这一本") })
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BookFilter.entries.forEach { filter ->
                    FilterChip(page.filter == filter, { onFilter(filter) }, { Text(filter.label) },
                        modifier = Modifier.testTag("filter:" + filter.label))
                }
            }
        }
        if (page.rows.isEmpty()) item { Sheet { Heading("这里还没有题", "可以调整筛选，或录入第一道题。") } }
        items(page.rows, key = { it.id }) { card ->
            Sheet(Modifier.fillMaxWidth().clickable { onCard(card) }) {
                Text(card.label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(status(card), style = MaterialTheme.typography.bodySmall,
                    color = if (card.conflict) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
