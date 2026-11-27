package app.pagereprise

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Choose the action on pointer down, leaving the search field and vertical scrolling to their owners. */
@Composable
internal fun Modifier.onNotebookSwipe(actionAt: (Float) -> ((Int) -> Unit)?): Modifier {
    val currentActionAt by rememberUpdatedState(actionAt)
    return pointerInput(Unit) {
        val minimumDistance = 48.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val action = currentActionAt(down.position.y) ?: return@awaitEachGesture
            var horizontal = false
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
                    if (!change.pressed && abs(delta.x) >= minimumDistance && abs(delta.x) > abs(delta.y)) {
                        action(if (delta.x < 0) 1 else -1)
                    }
                }
            } while (change.pressed)
        }
    }
}
