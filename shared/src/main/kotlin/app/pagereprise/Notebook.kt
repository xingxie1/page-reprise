package app.pagereprise

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.pagereprise.core.Card
import app.pagereprise.core.Library
import app.pagereprise.core.Subjects
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect

private enum class BookFilter(val label: String) {
    ALL("全部"), DUE("已到期"), MASTERED("已掌握"), CONFLICT("待核对")
}
private val bookLabels = listOf("普通错题本", "重点错题本")
private val filterLabels = BookFilter.entries.map { it.label }

private data class NotebookPage(val focus: Boolean, val filter: BookFilter, val query: String,
                                val book: List<Card>, val today: LocalDate) {
    val rows = book.filter { card ->
        (query.isBlank() || (card.label + " " + card.body.source + " " + card.body.tags.joinToString(" ")).contains(query, true) ||
            card.numberLabel.contains(query, true) || card.body.title.contains(query, true)) &&
            when (filter) {
                BookFilter.ALL -> true
                BookFilter.DUE -> card.due(today)
                BookFilter.MASTERED -> card.plan.due == null
                BookFilter.CONFLICT -> card.conflict
            }
    }
}

@Composable
internal fun Book(model: StudyModel, bookTurn: PageTurn) {
    BoxWithConstraints(Modifier.fillMaxSize()) { BookPanes(model, bookTurn, wide = maxWidth >= 760.dp) }
}

@Composable
private fun BookPanes(model: StudyModel, bookTurn: PageTurn, wide: Boolean) {
    var query by rememberSaveable(model.subject, model.focus) { mutableStateOf("") }
    var filter by rememberSaveable(model.subject, model.focus) { mutableStateOf(BookFilter.ALL) }
    var peek by rememberSaveable(model.subject, model.focus) { mutableStateOf("") }
    val page = remember(model.cards, model.today, model.subject, model.focus, filter, query) {
        NotebookPage(model.focus, filter, query, model.book, model.today)
    }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().testTag("book-pages")) {
            key(model.subject, page.focus) {
                NotebookFace(model, page, bookTurn, { query = it }, { filter = it }) { card ->
                    if (wide) peek = card.id else model.detail(card)
                }
            }
        }
        if (wide) {
            VerticalDivider()
            Box(Modifier.weight(1.15f)) {
                page.book.firstOrNull { it.id == peek }?.let { Details(model, it, widePreview = true) }
                    ?: Text("选一道题，在这里预览。", Modifier.padding(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotebookFace(model: StudyModel, page: NotebookPage, bookTurn: PageTurn, onSearch: (String) -> Unit,
                         onFilter: (BookFilter) -> Unit, onCard: (Card) -> Unit) {
    val filterTurn = rememberPageTurn { onFilter(BookFilter.entries[it]) }
    val bookIndex = if (page.focus) 1 else 0
    val nextBook = bookTurn.target?.let { index ->
        remember(index, model.cards, model.subject, model.today) {
            NotebookPage(index == 1, BookFilter.ALL, "", Library.book(model.cards, model.subject, index == 1), model.today)
        }
    }
    LaunchedEffect(nextBook?.focus) { if (nextBook != null) filterTurn.reset() }
    val listTurn = if (nextBook == null) filterTurn else bookTurn
    val list = key(page.filter) { rememberLazyListState() }
    var hidden by rememberSaveable { mutableFloatStateOf(0f) }
    var headerHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex > 0 }.collect { if (it) hidden = headerHeight.toFloat() }
    }
    val headerScroll = remember {
        object : NestedScrollConnection {
            fun shift(delta: Float): Offset {
                val before = hidden
                hidden = (before - delta).coerceIn(0f, headerHeight.toFloat())
                return Offset(0f, before - hidden)
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource) =
                if (available.y < 0) shift(available.y) else Offset.Zero
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                if (available.y > 0) shift(available.y) else Offset.Zero
        }
    }
    Column(Modifier.fillMaxSize().nestedScroll(headerScroll)) {
        // The header scrolls vertically and stays outside the horizontally sliding results.
        Column(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
            val header = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val offset = hidden.roundToInt().coerceIn(0, header.height)
            val reserve = minOf(96.dp.roundToPx(), constraints.maxHeight / 3)
            val visible = minOf(header.height - offset, constraints.maxHeight - reserve)
            layout(header.width, visible) { if (visible > 0) header.place(0, -offset) }
        }.onSizeChanged { headerHeight = it.height }
            .scrollable(rememberScrollableState { 0f }, Orientation.Vertical)) {
            Column(Modifier.fillMaxWidth().onNotebookSwipe(bookTurn, bookIndex, 1)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SlidingTabs(bookLabels, bookIndex, bookTurn, bookTurn.target, Modifier.fillMaxWidth().testTag("book-switcher"),
                    tag = { "choose-book:" + (it == 1) }, onSelect = { bookTurn.select(bookIndex, it) })
                SlidingPage(bookTurn, Modifier.fillMaxWidth(), next = { if (nextBook != null) BookActions(model, nextBook) }) {
                    BookActions(model, page)
                }
            }
            OutlinedTextField(page.query, onSearch, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("book-search"),
                singleLine = true, label = { Text("只搜索这一本") })
        }
        val filterSwipe = if (nextBook == null) Modifier.onNotebookSwipe(filterTurn, page.filter.ordinal, BookFilter.entries.lastIndex) else Modifier
        Column(Modifier.fillMaxWidth().weight(1f).then(filterSwipe)) {
            SlidingTabs(filterLabels, page.filter.ordinal, listTurn, if (nextBook == null) filterTurn.target else BookFilter.ALL.ordinal,
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp).testTag("book-filters"),
                tag = { "filter:" + filterLabels[it] }, onSelect = { if (nextBook == null) filterTurn.select(page.filter.ordinal, it) })
            SlidingPage(listTurn, Modifier.fillMaxWidth().weight(1f).testTag("filter-pages"), next = { index ->
                val nextPage = remember(page, index, nextBook) { nextBook ?: page.copy(filter = BookFilter.entries[index]) }
                ResultList(nextPage.rows, rememberLazyListState(), {})
            }) {
                ResultList(page.rows, list, onCard)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookActions(model: StudyModel, page: NotebookPage) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Subjects.name(model.subject) + " · 共 " + page.book.size + " 题",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { model.start(false) }, enabled = page.book.any { it.due(page.today) }) { Text("开始到期复习") }
            OutlinedButton(onClick = { model.start(true, page.rows) }, enabled = page.rows.any { !it.conflict }) { Text("手动抽练") }
            TextButton(onClick = { model.newEditor(model.subject) }) { Text("＋ 录题") }
            if (!page.focus) TextButton(onClick = model::openTrash) { Text("回收站") }
        }
    }
}

@Composable
private fun ResultList(rows: List<Card>, list: LazyListState, onCard: (Card) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("book-list"), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) item { Sheet { Heading("这里还没有题", "可以调整筛选，或录入第一道题。") } }
        items(rows, key = { it.id }) { card ->
            Sheet(Modifier.fillMaxWidth().clickable { onCard(card) }) {
                Text(card.label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(status(card), style = MaterialTheme.typography.bodySmall,
                    color = if (card.conflict) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
