package app.pagereprise

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Both faces and their tab indicator use the same fraction, read during drawing. */
@Composable
internal fun SlidingPage(turn: PageTurn, modifier: Modifier, next: @Composable (Int) -> Unit, current: @Composable () -> Unit) {
    Box(modifier.clipToBounds(), propagateMinConstraints = true) {
        Box(Modifier.fillMaxWidth().graphicsLayer {
            translationX = -turn.direction * turn.fraction * size.width
        }, propagateMinConstraints = true) { current() }
        turn.target?.let { index ->
            Box(Modifier.matchParentSize().graphicsLayer {
                translationX = turn.direction * (1f - turn.fraction) * size.width
            }.clearAndSetSemantics { }.pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
            }) { next(index) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SlidingTabs(labels: List<String>, selected: Int, turn: PageTurn, destination: Int?, modifier: Modifier,
                         tag: (Int) -> String, onSelect: (Int) -> Unit) {
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val colors = MaterialTheme.colorScheme
    val nominalHeight = with(LocalDensity.current) { 32.dp.toPx() }
    val active = if (turn.showTarget) destination ?: selected else selected
    FlowRow(modifier.drawBehind {
        val start = bounds[selected] ?: return@drawBehind
        val end = destination?.let(bounds::get) ?: start
        val fraction = if (destination == null) 0f else turn.fraction
        val pill = lerp(start, end, fraction)
        val radius = CornerRadius(8.dp.toPx())
        drawRoundRect(colors.secondaryContainer, pill.topLeft, pill.size, radius)
        val line = 1.dp.toPx()
        for ((index, rect) in bounds) {
            val weight = (if (index == selected) 1f - fraction else 0f) + (if (index == destination) fraction else 0f)
            if (weight < 1f) drawRoundRect(colors.outline.copy(alpha = 1f - weight),
                rect.topLeft + Offset(line / 2, line / 2), Size(rect.width - line, rect.height - line), radius, style = Stroke(line))
        }
    }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = index == active, onClick = { onSelect(index) }, label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, selectedContainerColor = Color.Transparent),
                border = null, modifier = Modifier.testTag(tag(index)).onPlaced { item ->
                    val pos = item.positionInParent()
                    val inset = ((item.size.height - nominalHeight) / 2f).coerceIn(0f, nominalHeight / 4f)
                    bounds[index] = Rect(pos.x, pos.y + inset, pos.x + item.size.width, pos.y + item.size.height - inset)
                })
        }
    }
}
