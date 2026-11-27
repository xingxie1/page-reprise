package app.pagereprise

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.WindowCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppearanceTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun launcherMaskPreservesTheLoopAtHomeScreenSize() {
        val activity = screen.activity
        val icon = activity.packageManager.getApplicationIcon(activity.applicationInfo) as AdaptiveIconDrawable
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(bitmap))
            assertEquals(Color.rgb(25, 29, 35), bitmap.getPixel(size / 2, size / 2))
            val brightColumns = (0 until size).filter { x ->
                (0 until size).any { y -> Color.green(bitmap.getPixel(x, y)) > 180 }
            }
            // The approved mark fills about 62% of the tile, with room for the launcher mask.
            assertTrue(brightColumns.first() in 15..21)
            assertTrue(brightColumns.last() in 74..80)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun dashboardUsesGraphiteAndLimeWithLightSystemBarIcons() {
        val hero = screen.onNodeWithTag("open-today").captureToImage().toPixelMap()
        val button = screen.onNodeWithTag("new-card").captureToImage().toPixelMap()
        assertEquals(Color.rgb(44, 52, 52), hero[hero.width * 4 / 5, hero.height * 3 / 4].toArgb())
        assertEquals(Color.rgb(215, 239, 116), button[button.width / 5, button.height / 2].toArgb())
        screen.runOnIdle {
            val window = screen.activity.window
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            assertFalse(bars.isAppearanceLightStatusBars)
            assertFalse(bars.isAppearanceLightNavigationBars)
        }
    }
}
