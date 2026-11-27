package app.pagereprise

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AlbumImportTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun bothImageButtonsUseThePhotoPickerAndKeepTheSelectedOrder() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val activity = ui.activity
        val resolver = activity.contentResolver
        val launch = AlbumPicker().createIntent(activity, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        assertEquals("image/*", launch.type)
        if (Build.VERSION.SDK_INT >= 35) {
            assertEquals(MediaStore.ACTION_PICK_IMAGES, launch.action)
            assertEquals(MediaStore.PICK_IMAGES_TAB_ALBUMS, launch.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB, -1))
            assertTrue(launch.getBooleanExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, false))
        } else if (launch.action == Intent.ACTION_OPEN_DOCUMENT) {
            assertNull(Intent(Intent.ACTION_PICK).setType("image/*").resolveActivity(activity.packageManager))
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val match = IntentFilter(requireNotNull(launch.action)).apply {
            addDataType("image/*")
            launch.categories.orEmpty().forEach(::addCategory)
        }
        val images = mutableListOf<Uri>()
        fun image(color: Int): Uri {
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "album-" + System.nanoTime() + ".png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PageRepriseTest")
            }))
            images.add(uri)
            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            return uri
        }
        fun select(button: String, data: Intent?, received: () -> Boolean) {
            val result = Instrumentation.ActivityResult(if (data == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, data)
            val monitor = instrumentation.addMonitor(match, result, true)
            try {
                ui.onNodeWithText(button).performScrollTo().performClick()
                ui.waitUntil(10000) { monitor.hits == 1 && received() }
            } finally { instrumentation.removeMonitor(monitor) }
        }
        try {
            val first = image(android.graphics.Color.BLUE)
            val second = image(android.graphics.Color.GREEN)
            ui.onNodeWithTag("new-card").performClick()
            val model = activity.model
            val stem = Intent().apply {
                clipData = ClipData.newUri(resolver, "题干", first).apply { addItem(ClipData.Item(second)) }
            }
            select("＋ 添加题干图", stem) { model.editor.body.pictures.size == 2 && !model.busy }
            val pictures = model.editor.body.pictures
            assertNotEquals(pictures[0], pictures[1])
            select("＋ 添加答案图", Intent().setData(second)) { model.editor.body.answers.size == 1 && !model.busy }
            assertEquals(listOf(pictures[1]), model.editor.body.answers)
            select("＋ 添加答案图", null) { !model.busy }
            assertEquals(pictures, model.editor.body.pictures)
            assertEquals(1, model.editor.body.answers.size)
        } finally { images.forEach { resolver.delete(it, null, null) } }
    }
}
