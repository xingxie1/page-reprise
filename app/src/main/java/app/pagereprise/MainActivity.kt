package app.pagereprise

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.viewModels
import app.pagereprise.core.*

class MainActivity : ComponentActivity() {
    private val holder: StudyViewModel by viewModels()
    val model get() = holder.model
    private var initialShare: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkBars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = darkBars, navigationBarStyle = darkBars)
        savedInstanceState?.getString("currentEditor")?.let { model.resumeEditor(it, savedInstanceState.getString("editorOwner")) }
        setContent { CompositionLocalProvider(LocalAndroidWindow provides window) { RepriseApp(model) } }
        val photos = sharedImages(intent)
        initialShare = photos.takeIf { it.isNotEmpty() }?.let { digest(it.joinToString("\n").toByteArray()) }
        if (initialShare != null && initialShare != savedInstanceState?.getString("initialShare")) model.shared(photos.map { inputFile(it) })
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("initialShare", initialShare)
        if (model.page == Page.EDITOR) {
            val snapshot = Codec.json.toJson(model.editor)
            if (snapshot.length <= 100000) outState.putString("currentEditor", snapshot)
            outState.putString("editorOwner", model.owner)
        }
    }
    override fun onResume() { super.onResume(); model.foreground() }
    override fun onPause() { model.updates?.background(); super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        model.shared(sharedImages(intent).map { inputFile(it) })
    }
    @Suppress("DEPRECATION")
    private fun sharedImages(intent: Intent?): List<Uri> {
        if (intent == null || intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)) return emptyList()
        val photos = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(photos::add)
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(photos::add)
            Intent.ACTION_SEND_MULTIPLE -> photos += intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
        intent.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(photos::add) }
        return photos.distinct().filter { it.scheme == "content" }
    }
}
