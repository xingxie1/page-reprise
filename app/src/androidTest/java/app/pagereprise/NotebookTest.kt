package app.pagereprise

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
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

    @Test fun captureRequiresSubjectAndSurvivesActivityRecreation() {
        val title = "积分换元 · " + newId().take(6)
        val model = ViewModelProvider(screen.activity)[StudyModel::class.java]
        screen.onNodeWithTag("new-card").performClick()
        screen.onNodeWithTag("question-text").performTextInput(title)
        screen.onNodeWithTag("save-card").assertIsNotEnabled()
        screen.onNodeWithText("数学二", useUnmergedTree = true).performScrollTo().performClick()
        screen.onNodeWithTag("save-card").performScrollTo().performClick()
        screen.waitUntil(10000) { model.cards.any { it.body.title == title } }
        screen.activityRule.scenario.recreate()
        screen.onNodeWithTag("book:math:false").performClick()
        screen.onNodeWithText(title).performClick()
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

    @Test fun sharedImageNeedsExplicitSubjectAndDraftHasNoSchedule() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val activity = screen.activity
        val model = ViewModelProvider(activity)[StudyModel::class.java]
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
            screen.onNodeWithText("仅存为本机草稿").performScrollTo().performClick()
            screen.waitUntil(10000) { model.drafts.any { it.draftId == draftId } }
            assertEquals(previous, model.cards.size)
            assertTrue(model.media.file(hash).length() > 0)
        } finally { resolver.delete(uri, null, null) }
    }
}
