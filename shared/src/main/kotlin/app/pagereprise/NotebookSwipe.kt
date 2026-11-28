package app.pagereprise

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import kotlin.math.sign

internal class PageTurn(private val scope: CoroutineScope, private val commit: (Int) -> Unit) {
    var target by mutableStateOf<Int?>(null); private set
    var direction by mutableIntStateOf(1); private set
    var showTarget by mutableStateOf(false); private set
    var fraction by mutableFloatStateOf(0f); private set
    private var animation: Job? = null
    fun hold() { animation?.cancel() }

    fun reset() {
        animation?.cancel()
        target = null
        showTarget = false
        fraction = 0f
    }

    fun drag(from: Int, to: Int, amount: Float) {
        direction = if (to > from) 1 else -1
        showTarget = amount > 0f
        fraction = amount.coerceIn(0f, 1f)
        target = to
    }

    fun finish(complete: Boolean) {
        val destination = target ?: return
        animation?.cancel()
        showTarget = complete
        animation = scope.launch {
            val end = if (complete) 1f else 0f
            animate(fraction, end, animationSpec = tween((280 * abs(end - fraction)).toInt().coerceAtLeast(90), easing = LinearOutSlowInEasing)) { value, _ ->
                fraction = value
            }
            if (complete) commit(destination)
            target = null
            showTarget = false
            fraction = 0f
        }
    }

    fun select(from: Int, to: Int) {
        if (from == to) { if (target != null) finish(false); return }
        if (target == to) { if (!showTarget || animation?.isActive != true) finish(true); return }
        animation?.cancel()
        drag(from, to, 0f)
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
        var horizontal = false
        var released = false
        var initial = 0f
        var interrupted: Int? = null
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.singleOrNull() ?: break
                if (change.id != down.id || change.isConsumed) break
                val delta = change.position - down.position
                if (!horizontal) {
                    if (abs(delta.y) >= viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) break
                    horizontal = abs(delta.x) >= viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y) * 1.25f
                    if (horizontal) {
                        turn.hold()
                        interrupted = turn.target
                        initial = turn.direction * turn.fraction
                    }
                }
                if (horizontal) {
                    change.consume()
                    val width = size.width.coerceAtLeast(1)
                    val distance = initial - delta.x / width
                    val direction = distance.sign.toInt()
                    val to = interrupted?.takeIf { (it - from).sign == direction } ?: (from + direction)
                    if (to != from && to in 0..last) {
                        turn.drag(from, to, abs(distance))
                    } else if (turn.target != null) {
                        turn.drag(from, turn.target!!, 0f)
                    }
                    if (!change.pressed) {
                        released = true
                        turn.finish(to != from && to in 0..last && abs(distance) * width >= minOf(minimum, width * .35f))
                    }
                }
            } while (change.pressed)
        } finally {
            if (horizontal && !released) turn.finish(false)
        }
    }
}
