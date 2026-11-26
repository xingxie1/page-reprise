package app.pagereprise.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StudyTest {
    private fun time(date: String) = LocalDate.parse(date).atTime(23, 59).atZone(StudyDays.zone).toInstant().toEpochMilli()
    private val first = Event.create("math", Body(title = "一道数二题"), time("2026-11-26"))
    private fun project(vararg events: Event) = Library.card(listOf(first, *events))!!
    private fun date(text: String) = LocalDate.parse(text)

    @Test fun uninterruptedAndLateRoutes() {
        val events = mutableListOf(first)
        listOf("2026-11-27", "2026-12-04", "2026-12-18").forEach { expected ->
            val card = Library.card(events)!!
            assertEquals(date(expected), card.plan.due)
            events += card.answer(true, time(expected))
        }
        assertNull(Library.card(events)!!.plan.due)
        assertEquals(date("2026-12-05"), project(project().answer(true, time("2026-11-28"))).plan.due)
        assertEquals(date("2026-11-27"), StudyDays.date(time("2026-11-26") + 120000))
    }

    @Test fun resetPromoteAndKeepSameSubjectAfterMastery() {
        val events = mutableListOf(first)
        val sequence = listOf("2026-11-27" to true, "2026-12-04" to false, "2026-12-05" to true,
            "2026-12-08" to false, "2026-12-09" to true, "2026-12-12" to true, "2026-12-26" to true)
        sequence.forEach { (expected, right) ->
            val card = Library.card(events)!!
            assertEquals(date(expected), card.plan.due)
            events += card.answer(right, time(expected))
        }
        val card = Library.card(events)!!
        assertEquals(2, card.plan.resets)
        assertTrue(card.plan.focus)
        assertNull(card.plan.due)
        assertEquals(1, Library.book(listOf(card), "math", true).size)
        assertTrue(Library.book(listOf(card), "cs", true).isEmpty())
        assertTrue(Library.book(listOf(card), "math", false).isEmpty())
    }

    @Test fun offlineDuplicatesConvergeAndSubsequentRoundSurvives() {
        val left = project().answer(false, time("2026-11-27"))
        val right = project().answer(false, time("2026-11-27") - 60000)
        val next = project(left).answer(true, time("2026-11-28"))
        val merged = project(next, right, left, right)
        assertEquals(1, merged.plan.resets)
        assertEquals(2, merged.history.size)
        assertEquals(date("2026-12-01"), merged.plan.due)
    }

    @Test fun differingAnswersAndDaysRequireExplicitChoice() {
        val yes = project().answer(true, time("2026-11-27"))
        for (other in listOf(project().answer(false, time("2026-11-27")), project().answer(true, time("2026-11-28")))) {
            val disputed = project(yes, other)
            assertTrue(disputed.scoreConflict)
            assertFalse(disputed.due(date("2026-12-04")))
            val decision = disputed.chooseVote(yes)
            val resolved = project(other, decision, yes)
            assertFalse(resolved.conflict)
            assertEquals(date("2026-12-04"), resolved.plan.due)
            assertEquals(resolved, project(decision, yes, other))
        }
    }

    @Test fun moveMergeUndoAndSecondResetProtection() {
        val wrong = project().answer(false, time("2026-11-27"))
        val moved = project().move("cs")
        val before = project(wrong, moved)
        assertEquals("cs", before.subject)
        assertEquals(1, before.plan.resets)
        assertEquals(0, project(wrong, moved, before.undo()).plan.resets)
        val again = before.answer(false, time("2026-11-28"))
        assertFalse(project(wrong, again).canUndo)
        assertTrue(project(wrong, again, before.undo()).plan.focus)
        val competing = project().move("english")
        val dispute = project(moved, competing)
        assertTrue(dispute.moveConflict)
        assertEquals("cs", project(moved, competing, dispute.move("cs")).subject)
    }

    @Test fun restoreOldEditsCannotRollBackNewerWork() {
        val edit = project().edit(Body(title = "改进后的题面"))
        val later = project(edit).edit(Body(title = "最终题面"))
        assertEquals("最终题面", project(later, edit, edit).body.title)
        assertEquals(first, Codec.decode(Codec.encode(first)))
        assertThrows(IllegalArgumentException::class.java) { project().answer(true, time("2026-11-26")) }
    }
}
