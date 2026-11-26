package app.pagereprise

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import app.pagereprise.core.*
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDate

enum class Page { HOME, BOOK, REVIEW, DETAIL, EDITOR, SETTINGS, ISSUES }
data class Turn(val card: String, val round: String)
data class Editor(val draft: String = newId(), val card: String? = null, val subject: String = "",
    val body: Body = Body(), val parents: List<String> = emptyList(), val back: Page = Page.HOME)

class StudyModel(private val app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val vault = Vault(app)
    private val prefs = app.getSharedPreferences("preferences", 0)
    private val cloud = Cloud()
    private val guest = prefs.getString("guest", null) ?: ("local:" + newId()).also { prefs.edit().putString("guest", it).apply() }
    var session by mutableStateOf(runCatching { vault.read() }.getOrNull()); private set
    val owner get() = session?.owner ?: guest
    val media get() = Media(app, owner)
    var cards by mutableStateOf(emptyList<Card>()); private set
    var drafts by mutableStateOf(emptyList<Draft>()); private set
    var pending by mutableIntStateOf(0); private set
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var syncing by mutableStateOf(false); private set
    var syncText by mutableStateOf(""); private set
    var lastSync by mutableLongStateOf(0); private set
    var message by mutableStateOf<String?>(null)
    var today by mutableStateOf(StudyDays.today())
    var page by mutableStateOf(Page.HOME); private set
    var subject by mutableStateOf("math"); private set
    var focus by mutableStateOf(false); private set
    var selected by mutableStateOf(""); private set
    var queue by mutableStateOf(emptyList<Turn>()); private set
    var manual by mutableStateOf(false); private set
    var editor by mutableStateOf(Editor()); private set
    var split by mutableStateOf(prefs.getBoolean("split:" + owner, false)); private set
    var preview by mutableStateOf<Staged?>(null); private set
    var mediaRevision by mutableIntStateOf(0); private set
    val serviceAddress get() = prefs.getString("service-site", "").orEmpty()
    val publishableKey get() = prefs.getString("service-public-key", "").orEmpty()
    private var refreshNumber = 0
    private var again = false
    val subjects get() = (Subjects.main.keys.filterNot { it == "cs" && split && cards.none { card -> card.subject == "cs" } } +
        Subjects.parts.keys.filter { key -> split || cards.any { it.subject == key } }).distinct()
    val selectable get() = if (split) Subjects.main.keys.filterNot { it == "cs" } + Subjects.parts.keys else subjects
    val book get() = Library.book(cards, subject, focus)

    init { viewModelScope.launch { reload() } }

    private suspend fun reload() {
        val scope = owner
        val ticket = ++refreshNumber
        val snapshot = withContext(Dispatchers.IO) {
            val rows = store.dao.entries(scope)
            Triple(Library.project(rows.map { Codec.decode(it.payload) }), store.dao.drafts(scope), rows.count { it.pending })
        }
        if (owner != scope || ticket != refreshNumber) return
        cards = snapshot.first
        drafts = snapshot.second
        pending = snapshot.third
        lastSync = prefs.getLong("synced:" + scope, 0)
        ready = true
    }

    private fun perform(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { work() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { message = failure.localizedMessage ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }

    fun foreground() {
        today = StudyDays.today()
        viewModelScope.launch { reload() }
        sync()
    }

    fun sync() {
        val auth = session ?: return
        if (syncing) { again = true; return }
        syncing = true
        viewModelScope.launch {
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
                    prefs.edit().putLong("synced:" + owner, System.currentTimeMillis()).apply()
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
    fun toggleSplit() { split = !split; prefs.edit().putBoolean("split:" + owner, split).apply() }
    fun openBook(key: String, important: Boolean) { subject = key; focus = important; page = Page.BOOK }
    fun settings() { page = Page.SETTINGS }
    fun issues() { page = Page.ISSUES }
    fun detail(card: Card) { subject = card.subject; focus = card.plan.focus; selected = card.id; page = Page.DETAIL }
    fun home() { page = Page.HOME }
    fun back() {
        if (busy) return
        page = when(page) {
            Page.REVIEW, Page.DETAIL -> Page.BOOK
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
    fun change(body: Body) { editor = editor.copy(body = body) }
    fun selectSubject(key: String) { editor = editor.copy(subject = key) }
    fun importImages(uris: List<Uri>, answers: Boolean) = perform {
        if (uris.isEmpty()) return@perform
        val files = media
        val count = if (answers) editor.body.answers.size else editor.body.pictures.size
        require(count + uris.size <= 16) { "每栏最多 16 张图" }
        val hashes = withContext(Dispatchers.IO) { uris.map(files::import) }
        val body = editor.body
        change(if (answers) body.copy(answers = body.answers + hashes) else body.copy(pictures = body.pictures + hashes))
    }
    fun shared(uris: List<Uri>) = perform {
        if (uris.isEmpty()) return@perform
        if (page == Page.EDITOR && editor.card == null && editor.body != Body()) {
            store.dao.saveDraft(Draft(owner, editor.draft, editor.subject, Codec.json.toJson(editor.body), System.currentTimeMillis()))
        } else if (page == Page.EDITOR && editor.card != null) {
            message = "请先完成当前编辑，再分享图片"; return@perform
        }
        newEditor()
        require(uris.size <= 16) { "一次最多 16 张" }
        val files = media
        val photos = withContext(Dispatchers.IO) { uris.map(files::import) }
        change(Body(pictures = photos))
        reload()
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
            store.db.withTransaction {
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
        vault.write(auth)
        session = auth; ready = false; cards = emptyList(); drafts = emptyList(); pending = 0
        editor = Editor(); queue = emptyList(); selected = ""; restoreCancel()
        split = prefs.getBoolean("split:" + owner, false)
        syncText = ""; page = Page.HOME
        reload(); sync()
    }
    fun login(site: String, key: String, email: String, pass: String, invite: String) = perform {
        check(!syncing)
        val auth = withContext(Dispatchers.IO) { cloud.login(site, key, email, pass, invite) }
        prefs.edit().putString("service-site", auth.site).putString("service-public-key", auth.key).apply()
        switch(auth)
    }
    fun logout() = perform { check(!syncing) { "请等本次同步结束" }; switch(null) }
    fun importGuest() = perform {
        require(session != null)
        val destination = media
        withContext(Dispatchers.IO) {
            val events = store.dao.entries(guest).map { Codec.decode(it.payload) }
            val origin = Media(app, guest)
            events.images().forEach { hash -> if (!destination.file(hash).exists()) origin.file(hash).inputStream().use { destination.accept(hash, it) } }
            store.merge(owner, events, true)
        }
        updated(); message = "本机试用题目已合并，未包含草稿"
    }

    fun export(uri: Uri) = perform {
        val scope = owner
        val files = media
        withContext(Dispatchers.IO) {
            val events = store.dao.entries(scope).map { Codec.decode(it.payload) }
            require(events.images().all { files.file(it).isFile }) { "请先同步所有题图再备份" }
            requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { out ->
                Backup.write(out, scope, events) { hash -> files.file(hash).inputStream() }
            }
        }
        message = "备份已导出，可手动上传夸克网盘"
    }
    fun stage(uri: Uri) = perform {
        restoreCancel()
        val scope = owner
        preview = withContext(Dispatchers.IO) {
            requireNotNull(app.contentResolver.openInputStream(uri)).use { Backup.preview(it, scope, app.cacheDir) }
        }
    }
    fun restoreCancel() { preview?.close(); preview = null }
    fun restore() = perform {
        val staged = requireNotNull(preview)
        require(staged.index.owner == owner)
        val files = media
        withContext(Dispatchers.IO) {
            staged.index.events.images().forEach { hash -> if (!files.file(hash).exists()) File(staged.directory, hash).inputStream().use { files.accept(hash, it) } }
            store.merge(owner, staged.index.events, true)
        }
        restoreCancel(); updated(); message = "已合并备份，不覆盖较新记录"
    }
    override fun onCleared() { restoreCancel(); super.onCleared() }
}
