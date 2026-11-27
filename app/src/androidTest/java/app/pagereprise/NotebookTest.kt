package app.pagereprise

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pagereprise.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotebookTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun touchSwitchesTheSubjectBookOrFilterFromTheStartOfTheSwipe() {
        val model = screen.activity.model
        screen.onNodeWithTag("subject:english").performScrollTo().performTouchInput {
            click(Offset(width * .78f, height * .18f))
        }
        screen.onNodeWithTag("choose-book:false").assertIsDisplayed().assertIsSelected()
        screen.onNodeWithTag("choose-book:true").assertIsDisplayed().assertIsNotSelected().performClick()
        val heading = screen.onNodeWithTag("book-switcher")
        heading.performTouchInput { swipeRight() }
        screen.runOnIdle { assertEquals("english", model.subject); assertFalse(model.focus) }
        heading.performTouchInput { swipeLeft() }
        heading.performTouchInput { swipeLeft() }
        screen.runOnIdle { assertTrue(model.focus) }
        val list = screen.onNodeWithTag("book-list")
        fun switchBelow(left: Boolean) = list.performTouchInput {
            val y = height * .88f
            val start = Offset(width * .8f, y)
            val end = Offset(width * .2f, y)
            if (left) swipe(start, end) else swipe(end, start)
        }
        for (filter in listOf("已到期", "已掌握", "待核对", "待核对")) {
            switchBelow(left = true)
            screen.onNodeWithTag("filter:" + filter).assertIsSelected()
        }
        switchBelow(left = false)
        screen.onNodeWithTag("filter:已掌握").assertIsSelected()
        screen.onNodeWithTag("book-search").performTouchInput { swipeRight() }
        screen.onNodeWithTag("filter:已掌握").assertIsSelected()
        screen.runOnIdle { assertTrue(model.focus) }
        screen.onNodeWithTag("choose-book:false").performClick()
        screen.onNodeWithTag("filter:全部").assertIsSelected()
        screen.onNodeWithText("‹ 返回").performClick()
        screen.onNodeWithTag("book:english:true").performScrollTo().performTouchInput { click(center) }
        screen.onNodeWithTag("choose-book:true").assertIsSelected()
        screen.runOnIdle { assertTrue(model.focus) }
    }

    @Test fun captureRequiresSubjectAndSurvivesActivityRecreation() {
        val title = "积分换元 · " + newId().take(6)
        val model = screen.activity.model
        screen.onNodeWithTag("new-card").performClick()
        screen.onNodeWithTag("question-text").performTextInput(title)
        screen.onNodeWithTag("save-card").assertIsNotEnabled()
        screen.onNodeWithText("数学", useUnmergedTree = true).performScrollTo().performClick()
        screen.onNodeWithTag("save-card").performScrollTo().performClick()
        screen.waitUntil(10000) { model.cards.any { it.body.title == title } }
        screen.activityRule.scenario.recreate()
        screen.onNodeWithTag("book:math:false").performClick()
        screen.onNodeWithText(title, substring = true).performClick()
        screen.onNodeWithText("题目详情").assertIsDisplayed()
        val wide = when (InstrumentationRegistry.getArguments().getString("layout")) {
            "tablet" -> true
            "phone" -> false
            else -> screen.activity.resources.configuration.screenWidthDp >= 760
        }
        if (wide) {
            screen.onNodeWithText("只搜索这一本").assertExists()
        } else {
            screen.onNodeWithText("只搜索这一本").assertDoesNotExist()
        }
        val item = model.cards.first { it.body.title == title }
        assertEquals(StudyDays.today().plusDays(1), item.plan.due)
        assertFalse(item.plan.focus)
    }

    @Test fun realRoomAndUiConfirmPromotionWithoutMixingSubjects() {
        val model = screen.activity.model
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

    @Test fun deleteRestoreThroughTheOrdinaryBook() {
        val model = screen.activity.model
        val store = Store(screen.activity.application)
        val title = "阅读主旨 · " + newId().take(8)
        val original = Event.create("english", Body(title = title))
        try {
            runBlocking { store.merge(model.owner, listOf(original), true) }
            screen.runOnIdle { model.foreground(); model.openBook("english", false) }
            screen.waitUntil(10000) { model.book.any { it.id == original.id } }
            screen.onNodeWithText(title, substring = true).performClick()
            screen.onNodeWithText("删除错题").performScrollTo().performClick()
            screen.onNodeWithText("取消").performClick()
            assertTrue(model.book.any { it.id == original.id })
            screen.onNodeWithText("删除错题").performScrollTo().performClick()
            screen.onNodeWithText("移入回收站").performClick()
            screen.waitUntil(10000) { model.page == Page.BOOK && model.discarded.any { it.id == original.id } }
            screen.onNodeWithText("回收站").performClick()
            screen.onNodeWithText(title, substring = true).assertIsDisplayed()
            screen.onNodeWithText("恢复到普通本").performClick()
            screen.waitUntil(10000) { model.book.any { it.id == original.id } }
            assertEquals(StudyDays.date(original.at).plusDays(1), model.book.single { it.id == original.id }.plan.due)
            screen.onNodeWithText(title, substring = true).assertDoesNotExist()
        } finally { store.db.close() }
    }

    @Test fun sharedImageNeedsExplicitSubjectAndDraftHasNoSchedule() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val activity = screen.activity
        val model = activity.model
        val resolver = activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "reprise-test-" + newId() + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PageRepriseTest")
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output ->
                val image = Bitmap.createBitmap(240, 90, Bitmap.Config.ARGB_8888)
                image.eraseColor(android.graphics.Color.WHITE)
                image.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            screen.waitUntil(10000) { model.ready }
            val previous = model.cards.size
            screen.runOnIdle {
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(resolver, "题图", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            screen.waitUntil(12000) { model.page == Page.EDITOR && model.editor.body.pictures.size == 1 && !model.busy }
            val hash = model.editor.body.pictures.single()
            val draftId = model.editor.draft
            assertTrue(model.editor.subject.isEmpty())
            screen.onNodeWithTag("save-card").assertIsNotEnabled()
            screen.onNodeWithText("数学", useUnmergedTree = true).performScrollTo().performClick()
            screen.runOnIdle {
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    setDataAndType(uri, "image/png")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
            screen.waitUntil(12000) { model.editor.body.answers.size == 1 && !model.busy }
            assertEquals(draftId, model.editor.draft)
            assertEquals("math", model.editor.subject)
            assertEquals(listOf(hash), model.editor.body.pictures)
            val tasks = (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks
            assertEquals(1, tasks.count { it.taskInfo.baseActivity?.className == MainActivity::class.java.name })
            screen.onNodeWithText("移到题干").performScrollTo().assertIsEnabled().performClick()
            screen.waitUntil(10000) { model.editor.body.pictures.size == 2 && model.editor.body.answers.isEmpty() }
            screen.onNodeWithText("仅存为本机草稿").performScrollTo().performClick()
            screen.waitUntil(10000) { model.drafts.any { it.draftId == draftId } }
            assertEquals(previous, model.cards.size)
            assertTrue(model.media.file(hash).length() > 0)
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun imageOpensFromTheBookAtFullWindowSize() {
        val activity = screen.activity
        val model = activity.model
        val store = Store(activity.application)
        try {
            val image = Bitmap.createBitmap(640, 240, Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.WHITE)
            val bytes = java.io.ByteArrayOutputStream().apply { image.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
            val hash = digest(bytes)
            val title = "看图 · " + newId().take(8)
            val original = Event.create("math", Body(title = title, pictures = listOf(hash)))
            model.media.accept(hash, bytes.inputStream())
            runBlocking { store.merge(model.owner, listOf(original), true) }
            screen.runOnIdle { model.foreground(); model.openBook("math", false) }
            screen.waitUntil(10000) { model.book.any { it.id == original.id } }
            screen.onNodeWithTag("book-list").performScrollToNode(hasText(title, substring = true))
            screen.onNodeWithText(title, substring = true).performClick()
            val label = "题干图，点按放大"
            screen.waitUntil(10000) { screen.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
            screen.onNodeWithContentDescription(label).performScrollTo().performClick()
            val viewer = screen.onNodeWithTag("photo-viewer").assertIsDisplayed().fetchSemanticsNode()
            val host = activity.window.decorView
            assertTrue("Viewer width " + viewer.size.width + " / " + host.width, viewer.size.width >= host.width - 2)
            assertTrue("Viewer height " + viewer.size.height + " / " + host.height, viewer.size.height >= host.height - 2)
            screen.onNodeWithContentDescription("关闭大图").performClick()
            screen.onNodeWithTag("photo-viewer").assertDoesNotExist()
        } finally { store.db.close() }
    }
    @Test fun todayListsDueCardsAcrossSubjectsAndReturnsAfterOneReview() {
        val model = screen.activity.model
        val store = Store(screen.activity.application)
        val morning = StudyDays.today().minusDays(3).atStartOfDay(StudyDays.zone).toInstant().toEpochMilli()
        val first = Event.create("cs-os", Body(title = "分页"), morning)
        val last = Event.create("english", Body(title = "主旨"), morning + 1)
        try {
            runBlocking { store.merge(model.owner, listOf(last, first), true) }
            screen.runOnIdle { model.foreground() }
            screen.waitUntil(10000) { model.cards.any { it.id == first.id } }
            screen.onNodeWithTag("open-today").performClick()
            assertEquals(listOf(first.id, last.id), model.dueCards.map { it.id }.filter { it == first.id || it == last.id })
            screen.onNodeWithTag("today-list").performScrollToNode(hasTestTag("today-" + first.id))
            screen.onNodeWithTag("today-" + first.id).performClick()
            screen.onNodeWithText("独立做对了").performScrollTo().performClick()
            screen.waitUntil(10000) { model.page == Page.TODAY && model.dueCards.none { it.id == first.id } }
            assertTrue(model.dueCards.any { it.id == last.id })
            screen.runOnIdle { model.newEditor() }
            screen.onNodeWithText("操作系统", useUnmergedTree = true).performScrollTo().assertExists()
            assertFalse("cs" in model.selectable)
            screen.runOnIdle { model.settings() }
            screen.onNodeWithText("408 学科设置").assertDoesNotExist()
            screen.onNodeWithText("复习规则").assertDoesNotExist()
        } finally { store.db.close() }
    }
}
