package app.pagereprise.core

/** A page belongs to one side of a question. Reordering images never changes their transcription. */
data class Readings(val questions: Map<String, String> = emptyMap(), val answers: Map<String, String> = emptyMap()) {
    fun side(answer: Boolean) = if (answer) answers else questions
}

fun Body.pages(answer: Boolean) = if (answer) answers else pictures
fun Body.reading(answer: Boolean, hash: String) = readings?.side(answer)?.get(hash)

fun Body.keepAttachedReadings(): Body = readings?.let {
    copy(readings = Readings(it.questions.filterKeys(pictures::contains), it.answers.filterKeys(answers::contains)))
} ?: this

fun Body.withReading(answer: Boolean, hash: String, markdown: String): Body {
    require(hash in pages(answer)) { "原图已移除" }
    val documents = readings ?: Readings()
    val text = markdown.trim()
    val side = if (text.isEmpty()) documents.side(answer) - hash else documents.side(answer) + (hash to text)
    return copy(readings = if (answer) documents.copy(answers = side) else documents.copy(questions = side))
        .also { it.checkReadings() }
}

internal fun Body.checkReadings() {
    val docs = readings ?: return
    require(docs.questions.keys.all(pictures::contains) && docs.answers.keys.all(answers::contains)) { "阅读稿缺少对应原图" }
    require((docs.questions.values + docs.answers.values).all { it.isNotBlank() && it.length <= 24000 }) { "单页阅读稿太长，请分图或精简文字" }
    // Leave space for event metadata, conflict references and JSONB formatting in the cloud row.
    if (docs.questions.isNotEmpty() || docs.answers.isNotEmpty())
        require(Codec.json.toJson(this).toByteArray().size <= 150000) { "单题文字过长，请将长题拆开录入" }
}

/** Old clients write edits without the additive field. Follow only ancestry that kept this exact image on this side. */
internal fun bodyWithReadings(chosen: Event, creation: Event, events: List<Event>): Body {
    val body = requireNotNull(chosen.body)
    if (chosen == creation || body.readings != null || events.none { it.body?.readings != null }) return body
    val byId = events.filter { it.kind == Kind.EDIT }.associateBy { it.id }
    fun inherit(answer: Boolean, hash: String): String? {
        val todo = ArrayDeque(listOf(chosen))
        val visited = mutableSetOf<String>()
        val versions = mutableSetOf<String?>()
        while (todo.isNotEmpty()) {
            val event = todo.removeFirst()
            if (!visited.add(event.id)) continue
            val parentBody = requireNotNull(event.body)
            if (hash !in parentBody.pages(answer) || parentBody.readings != null || event == creation)
                versions.add(parentBody.reading(answer, hash))
            else if (event.refs.isEmpty()) todo.addLast(creation)
            else event.refs.mapNotNullTo(todo, byId::get)
        }
        return versions.singleOrNull()
    }
    fun side(answer: Boolean) = body.pages(answer).mapNotNull { hash -> inherit(answer, hash)?.let { hash to it } }.toMap()
    return body.copy(readings = Readings(side(false), side(true)))
}

fun Card.markdown(): String = buildString {
    appendLine("# " + Subjects.name(subject) + " · " + label.substringBefore(' '))
    for (answer in listOf(false, true)) {
        appendLine()
        appendLine(if (answer) "## 答案与错因" else "## 题干")
        val manual = if (answer) body.solution else body.title
        if (manual.isNotBlank()) { appendLine(); appendLine(manual) }
        body.pages(answer).forEachIndexed { index, hash ->
            appendLine()
            appendLine(body.reading(answer, hash) ?: "〔第 " + (index + 1) + " 张图见循页原图〕")
        }
    }
    if (body.source.isNotBlank()) { appendLine(); appendLine("来源　" + body.source) }
}
