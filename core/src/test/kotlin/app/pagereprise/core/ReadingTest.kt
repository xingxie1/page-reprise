package app.pagereprise.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReadingTest {
    private val bytes = "picture".toByteArray()
    private val hash = digest(bytes)
    private val original = Body(pictures = listOf(hash), answers = listOf(hash))
    private val both = original.withReading(false, hash, "题面 \$x\$").withReading(true, hash, "解法")

    @Test fun oldEditsInheritOnlyStillAttachedPagesOnTheSameSide() {
        val create = Event.create("math", both)
        val edit = Library.card(listOf(create))!!.edit(original.copy(source = "第5页"))
        val old = Codec.decode(Codec.encode(edit))
        val card = Library.project(listOf(create, old)).single()
        assertEquals("#001", card.label)
        assertEquals(both.readings, card.body.readings)
        assertEquals("第5页", card.body.source)
        val removed = card.edit(Body(title = "题目", answers = listOf(hash)))
        val readded = Library.card(listOf(create, old, removed))!!.edit(original)
        val rebuilt = Library.card(listOf(create, old, removed, readded))!!
        assertNull(rebuilt.body.reading(false, hash))
        assertEquals("解法", rebuilt.body.reading(true, hash))
        val corrected = card.edit(card.body.withReading(true, hash, "校对后").withReading(false, hash, ""))
        val fixed = Library.card(listOf(create, old, corrected))!!
        assertNull(fixed.body.reading(false, hash))
        assertEquals("校对后", fixed.body.reading(true, hash))
        assertEquals(card.plan, fixed.plan)
        assertFalse(fixed.markdown().contains("解法"))
    }

    @Test fun archivesAndMarkdownKeepRolesFormulasAndPageOrder() {
        val event = Event.create("math", both)
        val temp = Files.createTempDirectory("reprise-readings").toFile()
        try {
            val titled = event.copy(body = both.withSuggestedTopic(false, hash, "数列极限"))
            for ((version, entry) in listOf(2 to event.copy(body = original), 3 to event, 4 to titled)) {
                val stream = ByteArrayOutputStream()
                Backup.write(stream, "owner", listOf(entry)) { bytes.inputStream() }
                Backup.preview(stream.toByteArray().inputStream(), "owner", temp).use {
                    assertEquals(version, it.index.version)
                    assertEquals(entry, it.index.events.single())
                }
            }
            val card = Library.project(listOf(event)).single()
            assertTrue(Library.project(listOf(titled)).single().markdown().startsWith("# 数学 · #001 · 数列极限"))
            assertTrue(card.markdown().contains("## 题干\n\n题面 \$x\$\n\n## 答案与错因\n\n解法"))
            assertNull(both.copy(answers = emptyList()).keepAttachedReadings().reading(true, hash))
            assertThrows(IllegalArgumentException::class.java) { both.withReading(false, hash, "a".repeat(24001)) }
        } finally { temp.deleteRecursively() }
    }

    @Test fun topicsFollowPageOrderManualChoiceAndEditsFromOlderClients() {
        val second = digest("second".toByteArray())
        val completed = both.copy(pictures = listOf(hash, second), title = "手填题干")
            .withSuggestedTopic(true, hash, "极限运算").withSuggestedTopic(false, second, "极限存在性")
            .withSuggestedTopic(false, hash, "数列极限")
        assertEquals("数列极限", completed.knowledgePoint)
        assertEquals("极限存在性", completed.copy(pictures = listOf(second, hash)).knowledgePoint)
        assertEquals("极限运算", completed.copy(pictures = emptyList()).keepAttachedReadings().knowledgePoint)
        val manual = completed.withManualTopic("夹逼准则")
        assertEquals("夹逼准则", manual.knowledgePoint)
        assertEquals("", manual.withManualTopic("").knowledgePoint)
        assertEquals("数列极限", manual.withManualTopic(null).knowledgePoint)
        val create = Event.create("math", manual)
        val old = Library.card(listOf(create))!!.edit(completed.copy(knowledge = null, source = "旧设备编辑"))
        val mixed = Library.project(listOf(old, create)).single()
        assertEquals("第 001 题 · 夹逼准则", mixed.label)
        assertEquals("手填题干", mixed.body.title)
        val cleared = mixed.edit(mixed.body.withManualTopic(""))
        val olderEdit = cleared.copy(id = newId(), refs = listOf(cleared.id), body = completed.copy(knowledge = null))
        assertEquals("#001  手填题干", Library.project(listOf(create, old, cleared, olderEdit)).single().label)
        val removed = mixed.edit(Body(title = "手填题干", answers = listOf(hash)))
        val readded = removed.copy(id = newId(), refs = listOf(removed.id), body = original)
        val restored = Library.project(listOf(create, old, removed, readded)).single().body
        assertEquals("极限运算", restored.suggestedTopic)
        assertEquals("夹逼准则", restored.knowledgePoint)
    }
}
