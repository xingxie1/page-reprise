package app.pagereprise

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import ru.noties.jlatexmath.JLatexMathDrawable

@Synchronized
internal fun formulaImage(latex: String, pixels: Float, color: Int) = runCatching {
    val formula = JLatexMathDrawable.builder(latex).textSize(pixels).color(color).build()
    require(formula.intrinsicWidth in 1..3072 && formula.intrinsicHeight in 1..1536 &&
        formula.intrinsicWidth.toLong() * formula.intrinsicHeight <= 1500000)
    val bitmap = Bitmap.createBitmap(formula.intrinsicWidth, formula.intrinsicHeight, Bitmap.Config.ARGB_8888)
    formula.draw(Canvas(bitmap))
    bitmap.asImageBitmap()
}.getOrNull()
