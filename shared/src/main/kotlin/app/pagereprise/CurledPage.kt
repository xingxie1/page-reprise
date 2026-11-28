package app.pagereprise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun CurledPage(turn: PageTurn, modifier: Modifier, next: @Composable (Int) -> Unit, current: @Composable () -> Unit) {
    val paper = MaterialTheme.colorScheme.background
    Box(modifier.clipToBounds()) {
        turn.target?.let { target ->
            Box(Modifier.matchParentSize().clearAndSetSemantics { }.pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
            }) { next(target) }
        }
        Box(Modifier.fillMaxSize().drawWithContent {
            val p = turn.fraction
            if (turn.target == null || p <= 0f) { drawContent(); return@drawWithContent }
            if (p >= 1f) return@drawWithContent
            val w = size.width
            val h = size.height
            val bend = sin(PI * p).toFloat()
            val center = w * (1f - p)
            val tilt = minOf(w * .24f, h * .32f) * bend * (if (turn.anchor >= .5f) 1f else -1f)
            val radius = minOf(w * .26f, 108.dp.toPx()) * bend
            val top = center + tilt
            val bottom = center - tilt
            val waist = center + radius * .28f
            val topLip = top - radius * (1.42f - turn.anchor)
            val bottomLip = bottom - radius * (.42f + turn.anchor)
            fun x(value: Float) = if (turn.forward) value else w - value
            fun Path.crease() = quadraticBezierTo(x(waist), h / 2f, x(bottom), h)

            val face = Path().apply {
                moveTo(x(0f), 0f); lineTo(x(top), 0f); crease(); lineTo(x(0f), h); close()
            }
            clipPath(face) { this@drawWithContent.drawContent() }

            val shade = radius * .42f
            val shadow = Path().apply {
                moveTo(x(top), 0f); crease(); lineTo(x(bottom + shade), h)
                quadraticBezierTo(x(waist + shade), h / 2f, x(top + shade), 0f); close()
            }
            // The gradients follow the diagonal fold, not the screen axes.
            val slope = (top - bottom) / h.coerceAtLeast(1f)
            val normal = Offset(if (turn.forward) 1f else -1f, slope) / (1f + slope * slope)
            val middle = Offset(x((center + waist) / 2f), h / 2f)
            drawPath(shadow, Brush.linearGradient(listOf(Color.Black.copy(alpha = .53f), Color.Transparent),
                middle, middle + normal * shade.coerceAtLeast(1f)))

            val back = Path().apply {
                moveTo(x(top), 0f); crease(); lineTo(x(bottomLip), h)
                quadraticBezierTo(x(waist - radius * 1.34f), h / 2f, x(topLip), 0f); close()
            }
            drawPath(back, paper)
            clipPath(back) {
                scale(-1f, 1f, middle) { this@drawWithContent.drawContent() }
            }
            val highlight = lerp(paper, Color(0xFFE9E5D7), .44f)
            val fold = Brush.linearGradient(0f to lerp(paper, Color.Black, .32f).copy(alpha = .91f),
                .26f to lerp(paper, highlight, .55f).copy(alpha = .90f), .70f to highlight.copy(alpha = .93f),
                1f to lerp(paper, Color.Black, .42f).copy(alpha = .97f),
                start = middle - normal * radius, end = middle)
            drawPath(back, fold)
            val edge = Path().apply {
                moveTo(x(topLip), 0f)
                quadraticBezierTo(x(waist - radius * 1.34f), h / 2f, x(bottomLip), h)
            }
            drawPath(edge, highlight.copy(alpha = .82f * bend), style = Stroke(1.dp.toPx()))
        }.graphicsLayer().background(paper)) { current() }
    }
}
