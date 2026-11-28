package app.pagereprise

import android.view.Window
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.core.view.*
import java.io.File

@Composable
internal fun brandIcon() = painterResource(R.drawable.reprise_loop)

internal fun Context.inputFile(uri: Uri) = InputFile {
    require(uri.scheme == "content") { "请选择系统分享或相册中的文件" }
    requireNotNull(contentResolver.openInputStream(uri))
}

internal class AlbumPicker : ActivityResultContracts.PickMultipleVisualMedia(16) {
    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent {
        val picker = super.createIntent(context, input)
        if (Build.VERSION.SDK_INT >= 35 && picker.action == MediaStore.ACTION_PICK_IMAGES) {
            picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB, MediaStore.PICK_IMAGES_TAB_ALBUMS)
            picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, true)
        }
        if (picker.action == Intent.ACTION_OPEN_DOCUMENT) {
            val gallery = Intent(Intent.ACTION_PICK).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            if (gallery.resolveActivity(context.packageManager) != null) return gallery
        }
        return picker
    }
}

@Composable
internal fun rememberImagePicker(receive: (List<InputFile>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(remember { AlbumPicker() }) { uris ->
        if (uris.isNotEmpty()) receive(uris.map(context::inputFile))
    }
    return { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
internal fun rememberDocumentExporter(markdown: Boolean = false, receive: (OutputFile) -> Unit): (String) -> Unit {
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (markdown) "text/markdown" else "application/zip")) { uri ->
        if (uri != null) receive(OutputFile { content -> requireNotNull(resolver.openOutputStream(uri, "wt")).use(content) })
    }
    return { name -> picker.launch(name) }
}

@Composable
internal fun rememberBackupImporter(receive: (InputFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> receive(context.inputFile(uri)) } }
    return { picker.launch(arrayOf("application/zip", "application/octet-stream")) }
}

@Composable internal fun PlatformBackHandler(enabled: Boolean, back: () -> Unit) = BackHandler(enabled, back)
@Composable internal fun CaptureTools(model: StudyModel) = Unit
@Composable internal fun Modifier.mousePhotoGestures(scale: () -> Float, change: (Float, Offset) -> Unit) = this

internal fun decodePhoto(file: File, limit: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > limit) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

internal val LocalAndroidWindow = staticCompositionLocalOf<Window> { error("Missing activity window") }

@Composable
internal fun ImmersivePhoto() {
    val window = LocalAndroidWindow.current
    val view = LocalView.current
    DisposableEffect(window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        val insets = ViewCompat.getRootWindowInsets(view)
        val status = WindowInsetsCompat.Type.statusBars()
        val navigation = WindowInsetsCompat.Type.navigationBars()
        val showStatus = insets?.isVisible(status) != false
        val showNavigation = insets?.isVisible(navigation) != false
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(status or navigation)
        onDispose {
            if (showStatus) controller.show(status)
            if (showNavigation) controller.show(navigation)
            controller.systemBarsBehavior = previousBehavior
        }
    }
}
