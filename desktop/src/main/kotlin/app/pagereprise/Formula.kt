package app.pagereprise

import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color
import java.awt.image.BufferedImage
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula

@Synchronized
internal fun formulaImage(latex: String, pixels: Float, color: Int) = runCatching {
    val icon = TeXFormula(latex).createTeXIcon(TeXConstants.STYLE_DISPLAY, pixels)
    require(icon.iconWidth in 1..3072 && icon.iconHeight in 1..1536 && icon.iconWidth.toLong() * icon.iconHeight <= 1500000)
    icon.setForeground(Color(color, true))
    val bitmap = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
    val graphics = bitmap.createGraphics()
    try { icon.paintIcon(null, graphics, 0, 0) } finally { graphics.dispose() }
    bitmap.toComposeImageBitmap()
}.getOrNull()
