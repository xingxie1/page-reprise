package app.pagereprise

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
private fun loaded(model: StudyModel, hash: String, limit: Int): ImageBitmap? {
    val file = remember(model.owner, hash) { model.media.file(hash) }
    val bitmap by produceState<ImageBitmap?>(null, file, limit, model.mediaRevision) {
        value = withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            var sample = 1
            while (maxOf(options.outWidth, options.outHeight) / sample > limit) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    return bitmap
}

@Composable
internal fun Photo(model: StudyModel, hash: String, label: String) {
    val bitmap = loaded(model, hash, 1024)
    var zoom by remember(hash) { mutableStateOf(false) }
    if (bitmap == null) {
        Text("图片载入中或尚未缓存，可在联网后同步。", style = MaterialTheme.typography.bodySmall)
        return
    }
    Image(bitmap, label, Modifier.fillMaxWidth().heightIn(max = 300.dp)
        .clip(RoundedCornerShape(8.dp)).clickable { zoom = true }, contentScale = ContentScale.Fit)
    if (zoom) Dialog(onDismissRequest = { zoom = false }) {
        val high = loaded(model, hash, 2800) ?: bitmap
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { factor, pan, _ ->
            scale = (scale * factor).coerceIn(1f, 6f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Surface(shape = RoundedCornerShape(14.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(.68f).clipToBounds().transformable(state), contentAlignment = Alignment.Center) {
                    Image(high, label, Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    }, contentScale = ContentScale.Fit)
                }
                TextButton(onClick = { zoom = false }) { Text("双指缩放 · 关闭") }
            }
        }
    }
}

@Composable
internal fun CropDialog(model: StudyModel, hash: String, cancel: () -> Unit, apply: (List<Float>) -> Unit) {
    val image = loaded(model, hash, 1800)
    var start by remember(hash) { mutableStateOf(Offset.Zero) }
    var end by remember(hash) { mutableStateOf(Offset(1f, 1f)) }
    AlertDialog(onDismissRequest = cancel, title = { Text("裁出这道题") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("在图上从一个角拖到对角。只生成裁切副本，不修改原图。")
                if (image != null) BoxWithConstraints(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    val ratio = image.width.toFloat() / image.height
                    val width = minOf(maxWidth, maxHeight * ratio)
                    Box(Modifier.size(width, width / ratio).pointerInput(hash) {
                        fun normalized(point: Offset) = Offset((point.x / size.width).coerceIn(0f, 1f), (point.y / size.height).coerceIn(0f, 1f))
                        detectDragGestures(onDragStart = { start = normalized(it); end = start }) { change, _ ->
                            end = normalized(change.position); change.consume()
                        }
                    }) {
                        Image(image, "选取裁切范围", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize()) {
                            val origin = Offset(minOf(start.x, end.x) * size.width, minOf(start.y, end.y) * size.height)
                            val area = Size(abs(start.x - end.x) * size.width, abs(start.y - end.y) * size.height)
                            drawRect(Color(0x4414634A), origin, area)
                            drawRect(Color(0xFF168A64), origin, area, style = Stroke(3.dp.toPx()))
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = image != null && abs(start.x-end.x) > .01f && abs(start.y-end.y) > .01f,
                onClick = { apply(listOf(minOf(start.x,end.x), minOf(start.y,end.y), maxOf(start.x,end.x), maxOf(start.y,end.y))) }) { Text("使用裁切图") }
        }, dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
}
