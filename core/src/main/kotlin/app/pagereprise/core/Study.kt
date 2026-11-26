package app.pagereprise.core

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

object StudyDays {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    fun date(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    fun today(): LocalDate = date(System.currentTimeMillis())
}

fun ByteArray.hex(): String = buildString(size * 2) {
    for (byte in this@hex) {
        val value = byte.toInt() and 255
        append("0123456789abcdef"[value ushr 4])
        append("0123456789abcdef"[value and 15])
    }
}

fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

fun newId(): String = UUID.randomUUID().toString()

object Subjects {
    val main = linkedMapOf("math" to "数学", "english" to "英语", "cs" to "408", "politics" to "政治")
    val parts = linkedMapOf("cs-ds" to "数据结构", "cs-co" to "计算机组成原理", "cs-os" to "操作系统", "cs-net" to "计算机网络")
    val all = main + parts
    fun name(key: String): String = all[key] ?: key
}

data class Body(
    val title: String = "",
    val source: String = "",
    val solution: String = "",
    val pictures: List<String> = emptyList(),
    val answers: List<String> = emptyList(),
    val tags: List<String> = emptyList()
) {
    fun checked(): Body {
        require(title.isNotBlank() || pictures.isNotEmpty()) { "请填写题干或添加题图" }
        require(title.length <= 12000 && solution.length <= 24000 && source.length <= 1000) { "文字太长" }
        require(pictures.size <= 16 && answers.size <= 16) { "题图和答案图各最多 16 张" }
        require(tags.size <= 24 && tags.all { it.isNotBlank() && it.length <= 60 })
        require((pictures + answers).all { it.matches(Regex("[0-9a-f]{64}")) }) { "图片标识无效" }
        return this
    }
}

const val TRASH_LIFETIME = 7L * 24 * 60 * 60 * 1000
enum class Kind { CREATE, REVIEW, UNDO, MOVE, EDIT, TRASH, RESTORE, PURGE }

data class Event(
    val id: String = newId(),
    val card: String = "",
    val kind: Kind = Kind.CREATE,
    val at: Long = System.currentTimeMillis(),
    val base: String = "",
    val value: String = "",
    val refs: List<String> = emptyList(),
    val body: Body? = null
) {
    fun checked(): Event {
        require(UUID.fromString(id).toString() == id && UUID.fromString(card).toString() == card)
        require(at in 946684800000L..7258118400000L)
        require(refs.size <= 512 && refs.all { UUID.fromString(it).toString() == it } && id !in refs)
        when (kind) {
            Kind.CREATE -> { require(id == card && value in Subjects.all && refs.isEmpty()); requireNotNull(body).checked() }
            Kind.EDIT -> requireNotNull(body).checked()
            Kind.MOVE -> require(value in Subjects.all)
            Kind.TRASH -> require(base.matches(Regex("[0-9a-f]{64}")) && body == null)
            Kind.RESTORE -> require(refs.isNotEmpty() && body == null)
            Kind.PURGE -> require(id == card && body == null && refs.isEmpty() && value.isEmpty() && base.isEmpty())
            Kind.REVIEW, Kind.UNDO -> {
                require(base == "start" || base.matches(Regex("[0-9a-f]{64}")))
                if (kind == Kind.REVIEW) require(value == "right" || value == "wrong")
                if (kind == Kind.UNDO) require(refs.isNotEmpty())
            }
        }
        return this
    }
    companion object {
        fun create(subject: String, body: Body, at: Long = System.currentTimeMillis()): Event {
            val card = newId()
            return Event(id = card, card = card, value = subject, body = body, at = at).checked()
        }
    }
}

data class Plan(val streak: Int, val resets: Int, val due: LocalDate?) {
    val focus: Boolean get() = resets >= 2
    fun answer(day: LocalDate, right: Boolean): Plan {
        require(due != null && !day.isBefore(due)) { "该轮尚未到期或已经完成" }
        if (!right) return Plan(0, resets + 1, day.plusDays(1))
        val interval = when (streak) { 0 -> if (resets == 0) 7L else 3L; 1 -> 14L; else -> null }
        return copy(streak = streak + 1, due = interval?.let(day::plusDays))
    }
}

data class Step(val round: String, val votes: List<Event>, val before: Plan, val after: Plan)
data class Card(
    val id: String, val subject: String, val body: Body, val plan: Plan, val round: String,
    val votes: List<Event>, val moves: List<Event>, val edits: List<Event>, val history: List<Step>,
    val scoreConflict: Boolean, val scoreVersion: String, val removals: List<Event>
) {
    val expiresAt get() = removals.minOfOrNull { it.at }?.plus(TRASH_LIFETIME)
    val trashed get() = removals.isNotEmpty()
    fun trash(at: Long = System.currentTimeMillis()): Event {
        require(!plan.focus && !trashed && !conflict) { "只能删除普通本中状态明确的题目" }
        return Event(card = id, kind = Kind.TRASH, base = scoreVersion, at = at)
    }
    fun restore(at: Long = System.currentTimeMillis()): Event {
        require(trashed && at < requireNotNull(expiresAt) && removals.all { at >= it.at }) { "这道题已超过恢复期限" }
        return Event(card = id, kind = Kind.RESTORE, refs = removals.ids(), at = at)
    }
    val moveConflict get() = moves.map { it.value }.distinct().size > 1
    val editConflict get() = edits.map { it.body }.distinct().size > 1
    val conflict get() = scoreConflict || moveConflict || editConflict
    val canUndo get() = !conflict && history.lastOrNull()?.let { it.before.focus == it.after.focus } == true
    fun due(day: LocalDate) = !trashed && !conflict && plan.due?.let { !it.isAfter(day) } == true
    fun answer(right: Boolean, at: Long = System.currentTimeMillis()): Event {
        require(due(StudyDays.date(at))) { "该轮已改变，请刷新" }
        return Event(card = id, kind = Kind.REVIEW, at = at, base = round,
            value = if (right) "right" else "wrong", refs = votes.ids())
    }
    fun undo(): Event {
        require(canUndo) { "这一轮不能撤销" }
        val step = history.last()
        return Event(card = id, kind = Kind.UNDO, base = step.round, refs = step.votes.ids())
    }
    fun chooseVote(chosen: Event): Event {
        require(scoreConflict && chosen in votes)
        return chosen.copy(id = newId(), refs = votes.ids())
    }
    fun move(target: String) = Event(card = id, kind = Kind.MOVE, value = target, refs = moves.ids()).checked()
    fun edit(updated: Body) = Event(card = id, kind = Kind.EDIT, body = updated, refs = edits.ids()).checked()
}

private fun List<Event>.ids() = map { it.id }.sorted()
private fun heads(nodes: List<Event>): List<Event> {
    val superseded = nodes.flatMap { it.refs }.toHashSet()
    return nodes.filterNot { it.id in superseded }.sortedBy { it.id }
}

private fun voteKey(event: Event): String = if (event.kind == Kind.UNDO) "undo"
    else event.value + ":" + StudyDays.date(event.at)
private fun successor(base: String, event: Event) = digest((base + "|" + voteKey(event)).toByteArray())

object Library {
    fun project(events: Collection<Event>): List<Card> = events.groupBy { it.card }.values.mapNotNull(::card)

    fun card(input: List<Event>): Card? {
        if (input.any { it.kind == Kind.PURGE }) return null
        val events = input.distinctBy { it.id }
        val creation = events.firstOrNull { it.kind == Kind.CREATE && it.id == it.card } ?: return null
        val moves = heads(events.filter { it.kind == Kind.MOVE })
        val edits = heads(events.filter { it.kind == Kind.EDIT })
        val subject = moves.map { it.value }.distinct().singleOrNull() ?: creation.value
        val body = edits.mapNotNull { it.body }.distinct().singleOrNull() ?: requireNotNull(creation.body)
        val rounds = events.filter { it.kind == Kind.REVIEW || it.kind == Kind.UNDO }.groupBy { it.base }
        var plan = Plan(0, 0, StudyDays.date(creation.at).plusDays(1))
        var round = "start"
        val history = mutableListOf<Step>()
        var active: List<Event>
        var conflict: Boolean
        while (true) {
            val proposals = rounds[round].orEmpty().filter { event ->
                if (event.kind == Kind.REVIEW) plan.due?.let { !StudyDays.date(event.at).isBefore(it) } == true
                else true
            }
            val lookup = proposals.associateBy { it.id }
            val eligible = proposals.filterNot { event ->
                event.kind == Kind.UNDO && event.refs.mapNotNull(lookup::get).any { old ->
                    old.kind == Kind.REVIEW && (rounds.containsKey(successor(round, old)) ||
                        (!plan.focus && plan.resets == 1 && old.value == "wrong"))
                }
            }
            active = heads(eligible)
            val alternatives = active.distinctBy(::voteKey)
            conflict = alternatives.size > 1
            val choice = alternatives.singleOrNull()
            if (choice == null || choice.kind == Kind.UNDO || plan.due == null) break
            val after = plan.answer(StudyDays.date(choice.at), choice.value == "right")
            history += Step(round, active, plan, after)
            plan = after
            round = successor(round, choice)
        }
        val scoreVersion = digest(rounds.values.flatten().ids().joinToString(",").toByteArray())
        val restores = events.filter { it.kind == Kind.RESTORE }
        val removals = if (plan.focus) emptyList() else events.filter { mark ->
            mark.kind == Kind.TRASH && mark.base == scoreVersion && restores.none { restoration ->
                mark.id in restoration.refs && restoration.at >= mark.at && restoration.at < mark.at + TRASH_LIFETIME
            }
        }.sortedBy { it.id }
        return Card(creation.card, subject, body, plan, round, active, moves, edits, history, conflict, scoreVersion, removals)
    }

    fun book(cards: List<Card>, subject: String, focus: Boolean): List<Card> =
        cards.filter { !it.trashed && it.subject == subject && it.plan.focus == focus }
            .sortedWith(compareBy<Card> { it.plan.due ?: LocalDate.MAX }.thenBy { it.id })
}
