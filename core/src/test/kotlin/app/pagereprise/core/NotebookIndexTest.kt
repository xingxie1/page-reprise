package app.pagereprise.core

import org.junit.Assert.*
import org.junit.Test

class NotebookIndexTest {
    private val day = java.time.LocalDate.of(2026, 11, 25)
    private val at = day.atStartOfDay(StudyDays.zone).toInstant().toEpochMilli()
    private fun create(subject: String, after: Long, number: Int? = null) =
        Event.create(subject, Body(title = "题干"), at + after).copy(number = number)

    @Test fun numbersBelongToSubjectsAndSurviveReviewTrashRestoreAndReordering() {
        val old = create("math", 0)
        val next = create("math", 1, 2)
        val english = create("english", 2, 1)
        val all = listOf(next, english, old)
        fun numbers(events: List<Event>) = Library.project(events).associate { it.id to it.number }
        val expected = mapOf(old.id to 1, next.id to 2, english.id to 1)
        val first = Library.card(listOf(old))!!
        val trash = first.trash(at + 5)
        val restored = Library.card(listOf(old, trash))!!.restore(at + 6)
        assertEquals(expected, numbers(all + trash + restored))
        assertEquals(expected, numbers(all.reversed() + trash))
        assertEquals(2, numbers(listOf(next))[next.id])
        val moved = first.move("english").copy(number = 2)
        assertEquals(2, numbers(all + moved)[old.id])
        assertEquals(1, numbers(all + moved)[english.id])
    }

    @Test fun offlineNumberCollisionsConvergeAndTodayIncludesOnlyDueInCreationOrder() {
        val early = create("math", 0, 1)
        val later = create("math", 1, 1)
        val english = create("english", 2)
        val onlyPhoto = create("cs-os", 3).copy(body = Body(pictures = listOf("0".repeat(64))))
        val future = create("math", 3 * 86_400_000)
        val deleted = create("english", 4)
        val events = listOf(future, onlyPhoto, early, english, later, deleted, Library.card(listOf(deleted))!!.trash(at + 5))
        val cards = Library.project(events)
        assertEquals(cards.associate { it.id to it.number }, Library.project(events.reversed()).associate { it.id to it.number })
        assertNotEquals(cards.first { it.id == early.id }.number, cards.first { it.id == later.id }.number)
        assertEquals("#001", cards.first { it.id == onlyPhoto.id }.label)
        assertEquals(listOf(early.id, later.id, english.id, onlyPhoto.id), Library.due(cards, day.plusDays(2)).map { it.id })
    }
}
