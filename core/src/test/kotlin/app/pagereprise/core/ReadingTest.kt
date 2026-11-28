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
            for ((version, entry) in listOf(2 to event.copy(body = original), 3 to event)) {
                val stream = ByteArrayOutputStream()
                Backup.write(stream, "owner", listOf(entry)) { bytes.inputStream() }
                Backup.preview(stream.toByteArray().inputStream(), "owner", temp).use {
                    assertEquals(version, it.index.version)
                    assertEquals(entry, it.index.events.single())
                }
            }
            val card = Library.project(listOf(event)).single()
            assertTrue(card.markdown().contains("## 题干\n\n题面 \$x\$\n\n## 答案与错因\n\n解法"))
            assertNull(both.copy(answers = emptyList()).keepAttachedReadings().reading(true, hash))
            assertThrows(IllegalArgumentException::class.java) { both.withReading(false, hash, "a".repeat(24001)) }
        } finally { temp.deleteRecursively() }
    }
}
