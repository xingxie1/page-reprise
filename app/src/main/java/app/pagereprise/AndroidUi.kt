package app.pagereprise

import android.view.Window
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.*
import java.io.File

internal fun Context.inputFile(uri: Uri) = InputFile {
    require(uri.scheme == "content") { "请选择系统分享或相册中的文件" }
    requireNotNull(contentResolver.openInputStream(uri))
}

@Composable
internal fun rememberImagePicker(receive: (List<InputFile>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        receive(it.map(context::inputFile))
    }
    return { picker.launch(arrayOf("image/*")) }
}

@Composable
internal fun rememberBackupExporter(receive: (OutputFile) -> Unit): (String) -> Unit {
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
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
