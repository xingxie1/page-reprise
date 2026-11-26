package app.pagereprise

import androidx.compose.runtime.*
import app.pagereprise.core.*
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDate

enum class Page { HOME, BOOK, REVIEW, DETAIL, EDITOR, SETTINGS, ISSUES, TRASH }
data class Turn(val card: String, val round: String)
data class AnswerShare(val editorId: String, val serial: Int)
data class ViewedPhoto(val hash: String, val label: String)
data class Editor(val draft: String = newId(), val card: String? = null, val subject: String = "",
    val body: Body = Body(), val parents: List<String> = emptyList(), val back: Page = Page.HOME)

class StudyModel(private val platform: StudyPlatform, private val scope: CoroutineScope) {
    private val store = platform.store
    private val vault = platform.vault
    private val prefs = platform.prefs
    private val cloud = Cloud()
    private val guest = prefs.getString("guest", null) ?: ("local:" + newId()).also { prefs.putString("guest", it) }
    var session by mutableStateOf(runCatching { vault.read() }.getOrNull()); private set
    val owner get() = session?.owner ?: guest
    val media get() = platform.media(owner)
    var cards by mutableStateOf(emptyList<Card>()); private set
    var discarded by mutableStateOf(emptyList<Card>()); private set
    var drafts by mutableStateOf(emptyList<Draft>()); private set
    var pending by mutableIntStateOf(0); private set
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var syncing by mutableStateOf(false); private set
    var syncText by mutableStateOf(""); private set
    var lastSync by mutableLongStateOf(0); private set
    var message by mutableStateOf<String?>(null)
    var today by mutableStateOf(StudyDays.today())
    private var currentPage by mutableStateOf(Page.HOME)
    var page: Page
        get() = currentPage
        private set(value) {
            if (currentPage == Page.EDITOR && value != Page.EDITOR) MediaFiles.protect(owner, null)
            currentPage = value
        }
    var subject by mutableStateOf("math"); private set
    var focus by mutableStateOf(false); private set
    var selected by mutableStateOf(""); private set
    var queue by mutableStateOf(emptyList<Turn>()); private set
    var manual by mutableStateOf(false); private set
    private var currentEditor by mutableStateOf(Editor())
    var editor: Editor
        get() = currentEditor
        private set(value) { currentEditor = value; MediaFiles.protect(owner, value.body) }
    var split by mutableStateOf(prefs.getBoolean("split:" + owner, false)); private set
    var preview by mutableStateOf<Staged?>(null); private set
    var mediaRevision by mutableIntStateOf(0); private set
    var answerShare by mutableStateOf<AnswerShare?>(null); private set
    var viewedPhoto by mutableStateOf<ViewedPhoto?>(null); private set
    fun viewPhoto(hash: String, label: String) { viewedPhoto = ViewedPhoto(hash, label) }
    fun closePhoto() { viewedPhoto = null }
    val serviceAddress get() = prefs.getString("service-site", "").orEmpty()
    val publishableKey get() = prefs.getString("service-public-key", "").orEmpty()
    private var refreshNumber = 0
    private var again = false
    private val shares = ArrayDeque<Pair<String, List<InputFile>>>()
    val subjects get() = (Subjects.main.keys.filterNot { it == "cs" && split && (cards + discarded).none { card -> card.subject == "cs" } } +
        Subjects.parts.keys.filter { key -> split || (cards + discarded).any { it.subject == key } }).distinct()
    val selectable get() = if (split) Subjects.main.keys.filterNot { it == "cs" } + Subjects.parts.keys else subjects
    val book get() = Library.book(cards, subject, focus)

    init { scope.launch { reload() } }

    private suspend fun reload() {
        val scope = owner
        val ticket = ++refreshNumber
        val snapshot = withContext(Dispatchers.IO) {
            store.maintain(scope)?.let { platform.scheduleCleanup(scope, it) }
            val rows = store.dao.entries(scope)
            Triple(Library.project(rows.map { Codec.decode(it.payload) }), store.dao.drafts(scope), rows.count { it.pending })
        }
        if (owner != scope || ticket != refreshNumber) return
        cards = snapshot.first.filterNot { it.trashed }
        discarded = snapshot.first.filter { (it.expiresAt ?: 0L) > System.currentTimeMillis() }
        drafts = snapshot.second
        pending = snapshot.third
        lastSync = prefs.getLong("synced:" + scope, 0)
        ready = true
    }

    private fun perform(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { work() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { message = failure.localizedMessage ?: "操作失败，请重试" }
            finally { busy = false; receiveNextShare() }
        }
    }

    fun foreground() {
        today = StudyDays.today()
        scope.launch { reload() }
        sync()
    }
    fun refreshTrash() { scope.launch { reload() } }
    fun openTrash() { if (!focus) page = Page.TRASH }
    fun deleteCard(card: Card) = perform {
        store.act(owner, card.id) { fresh ->
            require(fresh.subject == card.subject && fresh.scoreVersion == card.scoreVersion) { "题目已更新，请重新确认" }
            fresh.trash()
        }
        updated(); openBook(card.subject, false); message = "已移入回收站，7 天内可以恢复"
    }
    fun restoreCard(card: Card) = perform {
        store.act(owner, card.id, allowTrashed = true) { fresh -> fresh.restore() }
        updated(); message = "已恢复到" + Subjects.name(card.subject) + "普通本"
    }

    fun sync() {
        val auth = session ?: return
        if (syncing) { again = true; return }
        syncing = true
        scope.launch {
            try {
                do {
                    again = false
                    syncText = "正在同步…"
                    val current = withContext(Dispatchers.IO) { cloud.fresh(requireNotNull(session)) }
                    check(current.owner == auth.owner && auth.owner == owner)
                    if (session != current) { vault.write(current); session = current }
                    val files = media
                    withContext(Dispatchers.IO) {
                        cloud.sync(current, store, files) {
                            withContext(Dispatchers.Main) { reload(); syncText = "正在核对题图…" }
                        }
                    }
                    prefs.putLong("synced:" + owner, System.currentTimeMillis())
                    mediaRevision++
                    reload()
                    syncText = "已同步"
                } while (again)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { syncText = failure.localizedMessage ?: "同步未完成，请重试"; reload() }
            finally { syncing = false }
        }
    }

    private suspend fun updated() { reload(); sync() }
    fun toggleSplit() { split = !split; prefs.putBoolean("split:" + owner, split) }
    fun openBook(key: String, important: Boolean) { subject = key; focus = important; page = Page.BOOK }
    fun settings() { page = Page.SETTINGS }
    fun issues() { page = Page.ISSUES }
    fun detail(card: Card) { subject = card.subject; focus = card.plan.focus; selected = card.id; page = Page.DETAIL }
    fun home() { page = Page.HOME }
    fun back() {
        if (busy) return
        page = when(page) {
            Page.REVIEW, Page.DETAIL, Page.TRASH -> Page.BOOK
            Page.EDITOR -> editor.back
            else -> Page.HOME
        }
    }
    fun start(practice: Boolean, items: List<Card> = book) {
        val scoped = items.filter { it.subject == subject && it.plan.focus == focus && !it.conflict && (practice || it.due(today)) }
        if (scoped.isEmpty()) { message = "这本没有可复习的题"; return }
        manual = practice
        queue = scoped.map { Turn(it.id, it.round) }
        page = Page.REVIEW
    }
    fun next() {
        queue = queue.drop(1)
        if (queue.isEmpty()) { page = Page.HOME; message = "本次复习已结束，没有切换到其他题本" }
    }

    fun grade(card: Card, right: Boolean, approved: Boolean) = perform {
        check(!manual && queue.firstOrNull() == Turn(card.id, card.round))
        store.act(owner, card.id) { fresh ->
            check(fresh.subject == subject && fresh.plan.focus == focus && fresh.round == card.round) { "题目已更新，请重新开本" }
            require(right || fresh.plan.resets != 1 || approved) { "请先确认升入永久重点本" }
            fresh.answer(right)
        }
        updated()
        val state = cards.first { it.id == card.id }
        next()
        message = Subjects.name(state.subject) + (if (state.plan.focus) " · 永久重点" else " · 普通") +
            (state.plan.due?.let { "｜下次：" + it } ?: "｜本轮已掌握，不再排期")
    }

    fun newEditor(key: String = "") {
        editor = Editor(subject = key, back = if (page == Page.BOOK) Page.BOOK else Page.HOME)
        page = Page.EDITOR
    }
    fun editCard(card: Card) {
        editor = Editor(card = card.id, subject = card.subject, body = card.body, parents = card.edits.map { it.id }, back = Page.DETAIL)
        page = Page.EDITOR
    }
    fun draft(record: Draft) {
        editor = Editor(draft = record.draftId, subject = record.subject, body = Codec.json.fromJson(record.bodyJson, Body::class.java))
        page = Page.EDITOR
    }
    fun resumeEditor(snapshot: String, account: String?) {
        if (owner != account || page != Page.HOME) return
        val saved = Codec.json.fromJson(snapshot, Editor::class.java)
        editor = saved
        selected = saved.card.orEmpty()
        if (saved.subject in Subjects.all) subject = saved.subject
        page = Page.EDITOR
    }
    fun change(body: Body) { editor = editor.copy(body = body) }
    fun selectSubject(key: String) { editor = editor.copy(subject = key) }
    fun importImages(uris: List<InputFile>, answers: Boolean) = perform { appendImages(uris, answers) }
    private suspend fun appendImages(uris: List<InputFile>, answers: Boolean) {
        if (uris.isEmpty()) return
        val files = media
        val count = if (answers) editor.body.answers.size else editor.body.pictures.size
        require(count + uris.size <= 16) { "每栏最多 16 张图" }
        val hashes = withContext(Dispatchers.IO) { uris.map(files::import) }
        val body = editor.body
        change(if (answers) body.copy(answers = body.answers + hashes) else body.copy(pictures = body.pictures + hashes))
    }
    fun shared(uris: List<InputFile>) {
        if (uris.isNotEmpty()) {
            shares.addLast(owner to uris)
            receiveNextShare()
        }
    }
    private fun receiveNextShare() {
        if (busy || shares.isEmpty()) return
        val (account, photos) = shares.removeFirst()
        perform {
            if (account != owner) return@perform
            closePhoto()
            if (page != Page.EDITOR) newEditor()
            val answers = editor.body.pictures.isNotEmpty()
            appendImages(photos, answers)
            if (answers) answerShare = AnswerShare(editor.draft, (answerShare?.serial ?: 0) + 1)
        }
    }
    fun movePhoto(fromAnswers: Boolean, index: Int) {
        if (busy) return
        val body = editor.body
        val from = (if (fromAnswers) body.answers else body.pictures).toMutableList()
        val to = if (fromAnswers) body.pictures else body.answers
        if (index !in from.indices || to.size >= 16) return
        val moved = to + from.removeAt(index)
        change(if (fromAnswers) body.copy(answers = from, pictures = moved) else body.copy(pictures = from, answers = moved))
    }
    fun cropPhoto(answer: Boolean, position: Int, bounds: List<Float>) = perform {
        val body = editor.body
        val images = (if (answer) body.answers else body.pictures).toMutableList()
        val files = media
        images[position] = withContext(Dispatchers.IO) { files.crop(images[position], bounds[0], bounds[1], bounds[2], bounds[3]) }
        change(if (answer) body.copy(answers = images) else body.copy(pictures = images))
    }
    fun saveDraft() = perform {
        require(editor.card == null)
        val current = editor
        store.dao.saveDraft(Draft(owner, current.draft, current.subject, Codec.json.toJson(current.body), System.currentTimeMillis()))
        reload(); page = current.back; message = "已保存在本机草稿，不参加排期"
    }
    fun saveCard() = perform {
        val current = editor
        require(current.subject in Subjects.all) { "请先选择科目" }
        current.body.checked()
        val existing = current.card
        if (existing == null) {
            val event = Event.create(current.subject, current.body)
            store.transaction {
                store.merge(owner, listOf(event), true)
                store.dao.discard(owner, current.draft)
            }
            message = "已存入" + Subjects.name(current.subject) + " · 普通本，明天开始复习"
        } else {
            store.act(owner, existing) { fresh -> fresh.edit(current.body).copy(refs = current.parents) }
            message = "题目已保存，排期不变"
        }
        updated(); page = current.back
    }

    fun move(card: Card, target: String) = perform {
        store.act(owner, card.id) { fresh -> fresh.move(target).copy(refs = card.moves.map { it.id }) }
        updated(); detail(cards.first { it.id == card.id }); message = "科目已更新，历史与排期保留"
    }
    fun undo(card: Card) = perform {
        store.act(owner, card.id) { fresh -> check(fresh.round == card.round); fresh.undo() }
        updated(); message = "已撤销最近一轮"
    }
    fun resolve(card: Card, chosen: Event) = perform {
        store.act(owner, card.id) { fresh ->
            when(chosen.kind) {
                Kind.MOVE -> { require(chosen in fresh.moves && fresh.moves == card.moves) { "选项已更新，请重新核对" }; fresh.move(chosen.value) }
                Kind.EDIT -> { require(chosen in fresh.edits && fresh.edits == card.edits) { "选项已更新，请重新核对" }; fresh.edit(requireNotNull(chosen.body)) }
                else -> { require(fresh.votes == card.votes) { "作答已更新，请重新核对" }; fresh.chooseVote(chosen) }
            }
        }
        updated(); detail(cards.first { it.id == card.id }); message = "已保存核对结果"
    }

    private suspend fun switch(auth: Session?) {
        closePhoto()
        MediaFiles.protect(owner, null)
        vault.write(auth)
        session = auth; ready = false; cards = emptyList(); discarded = emptyList(); drafts = emptyList(); pending = 0
        editor = Editor(); queue = emptyList(); selected = ""; restoreCancel()
        split = prefs.getBoolean("split:" + owner, false)
        syncText = ""; page = Page.HOME
        reload(); sync()
    }
    fun login(site: String, key: String, email: String, pass: String, invite: String) = perform {
        check(!syncing)
        val auth = withContext(Dispatchers.IO) { cloud.login(site, key, email, pass, invite) }
        prefs.putString("service-site", auth.site)
        prefs.putString("service-public-key", auth.key)
        switch(auth)
    }
    fun logout() = perform { check(!syncing) { "请等本次同步结束" }; switch(null) }
    fun importGuest() = perform {
        require(session != null)
        withContext(Dispatchers.IO) {
            store.maintain(guest)
            val events = store.dao.entries(guest).map { Codec.decode(it.payload) }.filterNot { it.kind == Kind.PURGE }
            val origin = platform.media(guest)
            store.importWithImages(owner, events) { origin.file(it).inputStream() }
        }
        updated(); message = "本机试用题目已合并，未包含草稿"
    }

    fun export(destination: OutputFile) = perform {
        val scope = owner
        val files = media
        withContext(Dispatchers.IO) {
            store.maintain(scope)
            val events = store.dao.entries(scope).map { Codec.decode(it.payload) }.groupBy { it.card }.values.filterNot {
                Library.card(it)?.expiresAt?.let { end -> end <= System.currentTimeMillis() } == true
            }.flatten()
            require(events.images().all { files.file(it).isFile }) { "请先同步所有题图再备份" }
            destination.write { out ->
                Backup.write(out, scope, events) { hash -> files.file(hash).inputStream() }
            }
        }
        message = "备份已导出，可手动上传夸克网盘"
    }
    fun stage(source: InputFile) = perform {
        restoreCancel()
        val scope = owner
        preview = withContext(Dispatchers.IO) {
            source.open().use { Backup.preview(it, scope, platform.cacheDir) }
        }
    }
    fun restoreCancel() { preview?.close(); preview = null }
    fun restore() = perform {
        val staged = requireNotNull(preview)
        require(staged.index.owner == owner)
        withContext(Dispatchers.IO) {
            store.importWithImages(owner, staged.index.events) { File(staged.directory, it).inputStream() }
        }
        restoreCancel(); updated(); message = "已合并备份，不覆盖较新记录"
    }
    fun close() { MediaFiles.protect(owner, null); restoreCancel() }
}
