package app.pagereprise

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.pagereprise.core.*
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.SwingUtilities

class DesktopNotebookTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private lateinit var platform: StudyPlatform
    private lateinit var model: StudyModel
    private val job = SupervisorJob()

    @Before fun open() {
        platform = StudyPlatform(temp.newFolder())
        model = StudyModel(platform, CoroutineScope(job + Dispatchers.Main))
        ui.setContent { Box(Modifier.requiredSize(900.dp, 680.dp)) { RepriseApp(model) } }
        ui.waitUntil(10000) { model.ready }
    }

    @After fun close() {
        model.close()
        runBlocking { job.cancelAndJoin() }
        platform.store.close()
    }

    private fun paste(color: Int) {
        val image = BufferedImage(90, 45, BufferedImage.TYPE_INT_RGB).apply { setRGB(30, 20, color) }
        val clipboard = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = if (isDataFlavorSupported(flavor)) image else throw UnsupportedFlavorException(flavor)
        }
        ui.runOnIdle { model.shared(transferImages(clipboard)) }
        ui.waitUntil(10000) { !model.busy }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun subjectCardChooserAndMouseDragsUseTheStartingRegion() {
        val subject = ui.onNodeWithTag("subject:math")
        subject.performScrollTo().performMouseInput { click(Offset(width * .85f, height * .18f)) }
        ui.onNodeWithTag("choose-book:false").assertIsDisplayed().assertIsSelected()
        ui.onNodeWithTag("choose-book:true").assertIsDisplayed().assertIsNotSelected().performClick()
        ui.runOnIdle { assertEquals("math", model.subject); assertTrue(model.focus) }
        fun drag(node: SemanticsNodeInteraction, left: Boolean, y: Float = .5f) {
            node.performMouseInput {
                moveTo(Offset(width * (if (left) .8f else .2f), height * y))
                press()
                moveTo(Offset(width * (if (left) .2f else .8f), height * y), delayMillis = 120)
                release()
            }
        }
        val heading = ui.onNodeWithTag("book-switcher")
        val list = ui.onNodeWithTag("book-list")
        drag(heading, left = false)
        ui.runOnIdle { assertFalse(model.focus) }
        ui.onNodeWithTag("choose-book:false").assertIsSelected()
        drag(list, left = true, y = .85f)
        ui.onNodeWithTag("filter:已到期").assertIsSelected()
        drag(ui.onNodeWithTag("book-search"), left = true)
        ui.onNodeWithTag("filter:已到期").assertIsSelected()
        ui.runOnIdle { assertFalse(model.focus) }
        ui.onNodeWithTag("choose-book:true").performClick()
        ui.onNodeWithTag("filter:全部").assertIsSelected()
        ui.onNodeWithText("‹ 返回").performClick()
        ui.onNodeWithTag("book:math:true").performMouseInput { click() }
        ui.onNodeWithTag("choose-book:true").assertIsSelected()
        ui.runOnIdle { assertTrue(model.focus) }
    }

    @Test fun bookAndFilterPagesSlideInTheRequestedDirection() {
        ui.onNodeWithTag("book:math:false").performClick()
        val list = ui.onNodeWithTag("book-list")
        val preview = ui.onNodeWithText("选一道题，在这里预览。")
        val left = list.fetchSemanticsNode().positionInRoot.x
        val width = list.fetchSemanticsNode().size.width.toFloat()
        val previewBounds = preview.fetchSemanticsNode().boundsInRoot
        fun assertPageTurn(direction: Int, switch: () -> Unit) {
            ui.mainClock.autoAdvance = false
            try {
                switch()
                val moved = (1..24).any {
                    ui.mainClock.advanceTimeByFrame()
                    val distance = direction * (list.fetchSemanticsNode().positionInRoot.x - left)
                    distance > 1f && distance < width - 1f
                }
                assertTrue("An intermediate frame should show the incoming page", moved)
                assertEquals(previewBounds, preview.fetchSemanticsNode().boundsInRoot)
            } finally { ui.mainClock.autoAdvance = true }
            ui.waitForIdle()
            assertEquals(left, list.fetchSemanticsNode().positionInRoot.x, 1f)
        }
        assertPageTurn(1) {
            ui.onNodeWithTag("book-switcher").performTouchInput { swipeLeft() }
        }
        ui.onNodeWithTag("choose-book:true").assertIsSelected()
        ui.onNodeWithTag("filter:已到期").performClick().assertIsSelected()
        assertPageTurn(-1) {
            list.performTouchInput { swipe(Offset(width * .2f, height * .85f), Offset(width * .8f, height * .85f)) }
        }
        ui.onNodeWithTag("filter:全部").assertIsSelected()
    }

    @Test fun typingInTheSearchFieldKeepsTheCurrentPageAndQuery() {
        ui.onNodeWithTag("book:math:false").performClick()
        val search = ui.onNodeWithTag("book-search")
        search.performClick().performTextInput("二重")
        search.performTextInput("积分")
        search.assertIsFocused().assertTextContains("二重积分")
        ui.onNodeWithTag("filter:已到期").performClick()
        search.assertTextContains("二重积分")
        ui.onNodeWithTag("choose-book:true").performClick()
        search.assertTextEquals("只搜索这一本", "")
        ui.onNodeWithTag("filter:全部").assertIsSelected()
    }

    @Test fun imageImportStartsInTheWindowsPicturesFolder() {
        SwingUtilities.invokeAndWait {
            val chooser = imageFileChooser()
            val pictures = runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Pictures)) }.getOrNull()
            if (pictures?.isDirectory == true) assertEquals(pictures.canonicalFile, chooser.currentDirectory.canonicalFile)
            assertTrue(chooser.isMultiSelectionEnabled)
            assertTrue(chooser.fileFilter.accept(File("test.png")))
            assertFalse(chooser.fileFilter.accept(File("backup.zip")))
        }
    }

    @Test fun scrollingPastSearchLeavesRowsInTheFilterSwipeRegion() {
        val cards = (1..30).map { Event.create("math", Body(title = "练习 " + it)) }
        runBlocking { platform.store.merge(model.owner, cards, true) }
        ui.runOnIdle { model.foreground(); model.openBook("math", false) }
        ui.waitUntil(10000) { model.book.size == cards.size }
        val list = ui.onNodeWithTag("book-list")
        list.performTouchInput { swipeUp() }
        ui.runOnIdle { assertFalse(model.focus); assertEquals(Page.BOOK, model.page) }
        list.performScrollToIndex(20)
        ui.onNodeWithTag("book-search").assertDoesNotExist()
        list.performTouchInput { swipeLeft() }
        ui.onNodeWithTag("filter:已到期").assertIsSelected()
        ui.onNodeWithText("选一道题，在这里预览。").assertIsDisplayed()
    }

    @Test fun captureTwiceResumeDraftViewAndRestoreFromSubjectTrash() {
        ui.onNodeWithTag("new-card").performClick()
        paste(0x00FF00)
        ui.onNodeWithTag("save-card").assertIsNotEnabled()
        val draft = model.editor.draft
        ui.onNodeWithText("数学", useUnmergedTree = true).performScrollTo().performClick()
        paste(0xFF0000)
        assertEquals(draft, model.editor.draft)
        assertEquals(1, model.editor.body.pictures.size)
        assertEquals(1, model.editor.body.answers.size)
        ui.onNodeWithTag("question-text").performScrollTo().performTextInput("积分草稿")
        ui.onNodeWithText("仅存为本机草稿").performScrollTo().performClick()
        ui.waitUntil(10000) { model.page == Page.HOME && model.drafts.size == 1 }
        assertTrue(model.cards.isEmpty())
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("继续录入  ·  积分草稿"))
        ui.onNodeWithText("继续录入  ·  积分草稿").performClick()
        ui.onNodeWithTag("save-card").performScrollTo().performClick()
        ui.waitUntil(10000) { model.cards.size == 1 && model.page == Page.HOME }
        assertTrue(model.drafts.isEmpty())
        assertEquals(StudyDays.today().plusDays(1), model.cards.single().plan.due)
        ui.onNodeWithTag("book:math:false").performClick()
        ui.onNodeWithText("#001  积分草稿").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("题干图，点按放大").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("题干图，点按放大").performScrollTo().performClick()
        ui.onNodeWithTag("photo-viewer").assertExists()
        ui.onNodeWithContentDescription("关闭大图").performClick()
        ui.onNodeWithText("删除错题").performScrollTo().performClick()
        ui.onNodeWithText("移入回收站").performClick()
        ui.waitUntil(10000) { model.discarded.size == 1 && model.cards.isEmpty() }
        ui.onNodeWithText("回收站").performScrollTo().performClick()
        ui.onNodeWithText("恢复到普通本").performScrollTo().performClick()
        ui.waitUntil(10000) { model.discarded.isEmpty() && model.cards.size == 1 }
        assertEquals("math", model.cards.single().subject)
    }

    @Test fun guessingTwicePromotesOnlyWithinTheCurrentSubject() {
        val day = StudyDays.today()
        fun stamp(ago: Long) = day.minusDays(ago).atTime(13, 0).atZone(StudyDays.zone).toInstant().toEpochMilli()
        val created = Event.create("cs", Body(title = "缓存映射"), stamp(5))
        val wrong = Library.card(listOf(created))!!.answer(false, stamp(3))
        runBlocking { platform.store.merge(model.owner, listOf(created, wrong), true) }
        ui.runOnIdle { model.foreground() }
        ui.waitUntil(10000) { model.cards.size == 1 }
        ui.onNodeWithTag("book:cs:false").performScrollTo().performClick()
        ui.onNodeWithText("开始到期复习").performClick()
        ui.onNodeWithText("没做对（含猜对）").performScrollTo().performClick()
        ui.onNodeWithText("确认移入重点本").performClick()
        ui.waitUntil(10000) { model.cards.single().plan.focus }
        val card = model.cards.single()
        assertEquals(day.plusDays(1), card.plan.due)
        assertTrue(Library.book(model.cards, "math", true).isEmpty())
        assertTrue(Library.book(model.cards, "cs", false).isEmpty())
    }
    @Test fun todayOpensOneDueCardAndReturnsToTheSortedList() {
        val at = StudyDays.today().minusDays(2).atStartOfDay(StudyDays.zone).toInstant().toEpochMilli()
        val old = Event.create("cs-os", Body(title = "缺页"), at)
        val next = Event.create("english", Body(title = "段意"), at + 10)
        runBlocking { platform.store.merge(model.owner, listOf(next, old), true) }
        ui.runOnIdle { model.foreground() }
        ui.waitUntil(10000) { model.dueCards.size == 2 }
        ui.onNodeWithTag("open-today").performClick()
        assertEquals(listOf(old.id, next.id), model.dueCards.map { it.id })
        ui.onNodeWithTag("today-" + old.id).performClick()
        ui.onNodeWithText("独立做对了").performScrollTo().performClick()
        ui.waitUntil(10000) { model.page == Page.TODAY && model.dueCards.size == 1 }
        ui.onNodeWithTag("today-" + next.id).assertExists()
    }
}
