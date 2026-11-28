package app.pagereprise

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class PageTurn(private val scope: CoroutineScope, private val commit: (Int) -> Unit) {
    var target by mutableStateOf<Int?>(null); private set
    var forward by mutableStateOf(true); private set
    var anchor by mutableFloatStateOf(.8f); private set
    var fraction by mutableFloatStateOf(0f); private set
    private var animation: Job? = null
    val settling get() = animation?.isActive == true

    fun reset() {
        animation?.cancel()
        target = null
        fraction = 0f
    }

    fun drag(from: Int, to: Int, amount: Float, heightFraction: Float) {
        forward = to > from
        anchor = heightFraction.coerceIn(0f, 1f)
        fraction = amount.coerceIn(0f, 1f)
        target = to
    }

    fun finish(complete: Boolean) {
        val destination = target ?: return
        animation?.cancel()
        animation = scope.launch {
            val end = if (complete) 1f else 0f
            animate(fraction, end, animationSpec = tween((120 + 250 * abs(end - fraction)).toInt())) { value, _ ->
                fraction = value
            }
            if (complete) commit(destination)
            target = null
            fraction = 0f
        }
    }

    fun select(from: Int, to: Int) {
        if (from == to) { if (target != null) finish(false); return }
        if (target == to && settling) return
        animation?.cancel()
        drag(from, to, 0f, .82f)
        finish(true)
    }
}

@Composable
internal fun rememberPageTurn(commit: (Int) -> Unit): PageTurn {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(commit)
    return remember(scope) { PageTurn(scope) { latest(it) } }
}

/** Attach to the two swipe zones separately, leaving the search field to its own text gestures. */
internal fun Modifier.onNotebookSwipe(turn: PageTurn, from: Int, last: Int): Modifier = pointerInput(turn, from, last) {
    val minimum = 48.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (turn.settling) return@awaitEachGesture
        var horizontal = false
        var released = false
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.singleOrNull() ?: break
                if (change.id != down.id || change.isConsumed) break
                val delta = change.position - down.position
                if (!horizontal) {
                    if (abs(delta.y) >= viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) break
                    horizontal = abs(delta.x) >= viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y) * 1.25f
                }
                if (horizontal) {
                    change.consume()
                    val to = from + if (delta.x < 0) 1 else -1
                    val width = size.width.coerceAtLeast(1)
                    if (to in 0..last) {
                        turn.drag(from, to, abs(delta.x) / width, down.position.y / size.height.coerceAtLeast(1))
                    } else if (turn.target != null) {
                        turn.drag(from, turn.target!!, 0f, turn.anchor)
                    }
                    if (!change.pressed) {
                        released = true
                        turn.finish(to in 0..last && abs(delta.x) >= minOf(minimum, width * .35f) && abs(delta.x) > abs(delta.y))
                    }
                }
            } while (change.pressed)
        } finally {
            if (horizontal && !released) turn.finish(false)
        }
    }
}
