package app.pagereprise

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pagereprise.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotebookTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun captureRequiresSubjectAndSurvivesActivityRecreation() {
        val title = "积分换元 · " + newId().take(6)
        val model = ViewModelProvider(screen.activity)[StudyModel::class.java]
        screen.onNodeWithTag("new-card").performClick()
        screen.onNodeWithTag("question-text").performTextInput(title)
        screen.onNodeWithTag("save-card").assertIsNotEnabled()
        screen.onNodeWithText("数学二", useUnmergedTree = true).performClick()
        screen.onNodeWithTag("save-card").performScrollTo().performClick()
        screen.waitUntil(10000) { model.cards.any { it.body.title == title } }
        screen.activityRule.scenario.recreate()
        screen.onNodeWithTag("book:math:false").performClick()
        screen.onNodeWithText(title).assertExists()
        val item = model.cards.first { it.body.title == title }
        assertEquals(StudyDays.today().plusDays(1), item.plan.due)
        assertFalse(item.plan.focus)
    }

    @Test fun realRoomAndUiConfirmPromotionWithoutMixingSubjects() {
        val model = ViewModelProvider(screen.activity)[StudyModel::class.java]
        val store = Store(screen.activity.application)
        val day = StudyDays.today()
        fun stamp(ago: Long) = day.minusDays(ago).atTime(14, 0).atZone(StudyDays.zone).toInstant().toEpochMilli()
        val first = Event.create("cs", Body(title = "测试：进程的状态"), stamp(4))
        val wrong = Library.card(listOf(first))!!.answer(false, stamp(2))
        try {
            runBlocking { store.merge(model.owner, listOf(first, wrong), true) }
            screen.runOnIdle { model.foreground() }
            screen.waitUntil(10000) { model.cards.any { it.id == first.card } }
            screen.runOnIdle {
                model.openBook("cs", false)
                model.start(false, model.cards.filter { it.id == first.card })
            }
            screen.onNodeWithText("没做对（含猜对）").performScrollTo().performClick()
            screen.onNodeWithText("取消").performClick()
            assertEquals(1, model.cards.first { it.id == first.card }.plan.resets)
            screen.onNodeWithText("没做对（含猜对）").performScrollTo().performClick()
            screen.onNodeWithText("确认移入重点本").performClick()
            screen.waitUntil(10000) { model.cards.firstOrNull { it.id == first.card }?.plan?.focus == true }
            val saved = runBlocking { Library.card(store.dao.item(model.owner, first.card).map { Codec.decode(it.payload) })!! }
            assertEquals("cs", saved.subject)
            assertEquals(day.plusDays(1), saved.plan.due)
            assertTrue(Library.book(listOf(saved), "math", true).isEmpty())
        } finally { store.db.close() }
    }
}
