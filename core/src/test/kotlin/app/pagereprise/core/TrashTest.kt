package app.pagereprise.core

import org.junit.Assert.*
import org.junit.Test

class TrashTest {
    private val created = Event.create("math", Body(title = "微分"), 1800000000000L)
    private val tomorrow = created.at + 86400000
    private fun card(vararg events: Event) = requireNotNull(Library.card(listOf(created, *events)))

    @Test fun recycleBinRestoresSameBookAndPlanOnlyBeforeTheExactDeadline() {
        val right = card().answer(true, tomorrow)
        val original = card(right)
        val remove = original.trash(tomorrow + 1000)
        val bin = card(right, remove)
        val end = remove.at + TRASH_LIFETIME
        assertEquals(end, bin.expiresAt)
        assertTrue(Library.book(listOf(bin), "math", false).isEmpty())
        assertFalse(bin.due(StudyDays.date(end)))
        val restore = bin.restore(end - 1)
        val back = card(right, restore, remove)
        assertFalse(back.trashed)
        assertEquals(original.plan, back.plan)
        assertEquals(original.history, back.history)
        assertThrows(IllegalArgumentException::class.java) { bin.restore(end) }
        assertTrue(card(right, remove, restore.copy(at = end)).trashed)
        assertTrue(card(right, remove, restore, back.trash(end + 70)).trashed)
    }

    @Test fun aConcurrentReviewProtectsTheQuestionAndFocusCannotBeDeleted() {
        val firstWrong = card().answer(false, tomorrow)
        val ordinary = card(firstWrong)
        val mark = ordinary.trash(tomorrow + 100)
        val secondWrong = ordinary.answer(false, tomorrow + 86400000)
        val permanent = card(mark, secondWrong, firstWrong)
        assertTrue(permanent.plan.focus)
        assertFalse(permanent.trashed)
        assertThrows(IllegalArgumentException::class.java) { permanent.trash() }
        val purge = Event(id = created.id, card = created.id, kind = Kind.PURGE, at = tomorrow + TRASH_LIFETIME)
        assertNull(Library.card(listOf(purge, created, mark)))
        assertNull(Library.card(listOf(created, mark, purge)))
    }
}
