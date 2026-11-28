package app.pagereprise.core

/** Text keyed by the image hash and its role, used for both Markdown and short topic suggestions. */
data class Readings(val questions: Map<String, String> = emptyMap(), val answers: Map<String, String> = emptyMap()) {
    fun side(answer: Boolean) = if (answer) answers else questions
    fun attached(body: Body) = copy(questions = questions.filterKeys(body.pictures::contains), answers = answers.filterKeys(body.answers::contains))
    fun withPage(answer: Boolean, hash: String, text: String): Readings {
        val updated = if (text.isBlank()) side(answer) - hash else side(answer) + (hash to text)
        return if (answer) copy(answers = updated) else copy(questions = updated)
    }
}

const val TOPIC_LIMIT = 40
data class KnowledgePoint(val manual: String? = null, val suggestions: Readings = Readings())

fun Body.pages(answer: Boolean) = if (answer) answers else pictures
fun Body.reading(answer: Boolean, hash: String) = readings?.side(answer)?.get(hash)
val Body.suggestedTopic: String? get() = knowledge?.suggestions?.let { notes ->
    pictures.firstNotNullOfOrNull(notes.questions::get) ?: answers.firstNotNullOfOrNull(notes.answers::get)
}
val Body.knowledgePoint get() = (knowledge?.manual ?: suggestedTopic).orEmpty().trim()
fun validTopicText(text: String) = text.length <= TOPIC_LIMIT && text.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }

fun Body.keepAttachedReadings() = copy(readings = readings?.attached(this),
    knowledge = knowledge?.let { it.copy(suggestions = it.suggestions.attached(this)) })

fun Body.withManualTopic(value: String?) = copy(knowledge = (knowledge ?: KnowledgePoint()).copy(manual = value))

fun Body.withSuggestedTopic(answer: Boolean, hash: String, value: String?): Body {
    val topic = value?.trim()?.takeIf { it.isNotEmpty() && validTopicText(it) } ?: return this
    require(hash in pages(answer)) { "原图已移除" }
    val point = knowledge ?: KnowledgePoint()
    return copy(knowledge = point.copy(suggestions = point.suggestions.withPage(answer, hash, topic))).also { it.checkReadings() }
}

fun Body.withReading(answer: Boolean, hash: String, markdown: String): Body {
    require(hash in pages(answer)) { "原图已移除" }
    val documents = readings ?: Readings()
    return copy(readings = documents.withPage(answer, hash, markdown.trim())).also { it.checkReadings() }
}

internal fun Body.checkReadings() {
    readings?.let { docs ->
        require(docs.questions.keys.all(pictures::contains) && docs.answers.keys.all(answers::contains)) { "识别文字缺少对应原图" }
        require((docs.questions.values + docs.answers.values).all { it.isNotBlank() && it.length <= 24000 }) { "单张图的文字太长，请分图或精简文字" }
    }
    knowledge?.let { point ->
        val notes = point.suggestions
        require(notes.questions.keys.all(pictures::contains) && notes.answers.keys.all(answers::contains)) { "知识点缺少对应原图" }
        require(point.manual?.let(::validTopicText) != false &&
            (notes.questions.values + notes.answers.values).all { it.isNotBlank() && validTopicText(it) }) { "知识点请填写 40 字以内的单行名称" }
    }
    // Leave space for event metadata, conflict references and JSONB formatting in the cloud row.
    if (knowledge != null || readings?.let { it.questions.isNotEmpty() || it.answers.isNotEmpty() } == true)
        require(Codec.json.toJson(this).toByteArray().size <= 150000) { "单题文字过长，请将长题拆开录入" }
}

/** Older clients omit newer fields. For per-image text, inherit only across edits that kept the same image and role. */
internal fun bodyWithPageMetadata(chosen: Event, creation: Event, events: List<Event>): Body {
    val body = requireNotNull(chosen.body)
    if (chosen == creation) return body
    val inheritReading = body.readings == null && events.any { it.body?.readings != null }
    val inheritTopic = body.knowledge == null && events.any { it.body?.knowledge != null }
    if (!inheritReading && !inheritTopic) return body
    val byId = events.filter { it.kind == Kind.EDIT }.associateBy { it.id }
    fun inherit(stop: (Body) -> Boolean, value: (Body) -> String?): String? {
        val todo = ArrayDeque(listOf(chosen))
        val visited = mutableSetOf<String>()
        val versions = mutableSetOf<String?>()
        while (todo.isNotEmpty()) {
            val event = todo.removeFirst()
            if (!visited.add(event.id)) continue
            val parentBody = requireNotNull(event.body)
            if (stop(parentBody) || event == creation) versions.add(value(parentBody))
            else if (event.refs.isEmpty()) todo.addLast(creation)
            else event.refs.mapNotNullTo(todo, byId::get)
        }
        return versions.singleOrNull()
    }
    fun inheritedPages(field: (Body) -> Readings?): Readings {
        fun side(answer: Boolean) = body.pages(answer).mapNotNull { hash ->
            inherit({ hash !in it.pages(answer) || field(it) != null }, { field(it)?.side(answer)?.get(hash) })?.let { hash to it }
        }.toMap()
        return Readings(side(false), side(true))
    }
    return body.copy(readings = if (inheritReading) inheritedPages { it.readings } else body.readings,
        knowledge = if (inheritTopic) KnowledgePoint(inherit({ it.knowledge != null }, { it.knowledge?.manual }),
            inheritedPages { it.knowledge?.suggestions }) else body.knowledge)
}

fun Card.markdown(): String = buildString {
    appendLine("# " + Subjects.name(subject) + " · " + numberLabel + body.knowledgePoint.takeIf(String::isNotBlank)?.let { " · " + it }.orEmpty())
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
