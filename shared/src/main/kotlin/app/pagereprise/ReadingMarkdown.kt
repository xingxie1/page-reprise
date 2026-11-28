package app.pagereprise

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.parser.Parser

private val parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
// Keep code and escaped punctuation untouched while CommonMark parses the surrounding text.
private val tokens = Regex("""(?ms)^ {0,3}(`{3,}|~{3,})[^\n]*\n.*?(?:^ {0,3}\1[ \t]*$|\z)|(`+)[^\n]*?\2|\\\[.*?\\\]|\\\(.*?\\\)|\$\$.*?\$\$|(?<!\\)\$(?![\s$])(?:\\.|[^$\n])+?(?<!\\)\$|\\.""")
private val marker = Regex("\uE000(\\d+)\uE001")
private val commands = Regex("""\\([a-zA-Z]+)""")
private val allowed = """
    frac dfrac tfrac binom sqrt overline underline overrightarrow vec hat widehat bar tilde widetilde dot ddot
    sum prod int iint iiint oint lim limsup liminf max min sup inf det dim ker rank gcd mod bmod pmod
    sin cos tan cot sec csc arcsin arccos arctan sinh cosh tanh log ln exp sgn
    alpha beta gamma delta epsilon varepsilon zeta eta theta vartheta iota kappa lambda mu nu xi pi varpi rho
    varrho sigma varsigma tau upsilon phi varphi chi psi omega Gamma Delta Theta Lambda Xi Pi Sigma Upsilon Phi Psi Omega
    infty partial nabla ell hbar imath jmath Re Im emptyset varnothing forall exists neg land lor lnot implies iff
    pm mp times div cdot ast star circ bullet oplus otimes odot cap cup bigcap bigcup setminus wedge vee
    le leq leqslant ge geq geqslant ne neq approx equiv sim simeq cong propto ll gg parallel perp mid nmid
    in notin ni subset subseteq supset supseteq subsetneq supsetneq prec succ to mapsto gets rightarrow leftarrow
    leftrightarrow Rightarrow Leftarrow Leftrightarrow longrightarrow longleftarrow Longrightarrow Longleftrightarrow
    uparrow downarrow lfloor rfloor lceil rceil langle rangle vert Vert lvert rvert lVert rVert
    dots ldots cdots vdots ddots colon quad qquad mathrm mathit mathbf mathsf mathtt mathcal mathbb mathfrak
    text textrm textnormal operatorname left right big Big bigg Bigg bigl bigr Bigl Bigr biggl biggr
    begin end cases matrix pmatrix bmatrix vmatrix Vmatrix aligned gathered limits nolimits displaystyle textstyle
    overset underset stackrel underbrace overbrace angle degree top bot triangle hom Pr complement not prime
""".trim().split(Regex("\\s+")).toSet()
private val environments = Regex("""\\(?:begin|end)\s*\{([^}]*)\}""")
private val allowedEnvironments = setOf("cases", "matrix", "pmatrix", "bmatrix", "vmatrix", "Vmatrix", "aligned", "gathered")

internal fun renderableFormula(tex: String): Boolean {
    if (tex.length !in 1..1500 || "^^" in tex || '%' in tex || '#' in tex) return false
    var depth = 0
    for (ch in tex) {
        if (ch == '{') depth++ else if (ch == '}') depth--
        if (depth !in 0..24) return false
    }
    if (depth != 0 || commands.findAll(tex).any { it.groupValues[1] !in allowed }) return false
    // Environments need an explicit, supported literal name.
    val clean = environments.replace(tex) { if (it.groupValues[1] in allowedEnvironments) "" else it.value }
    return "\\begin" !in clean && "\\end" !in clean
}

private data class MathPiece(val text: String, val block: Boolean, val bitmap: ImageBitmap?)
private data class Document(val root: Node, val math: List<MathPiece>)

private fun parseReading(text: String, pixels: Float, color: Int): Document {
    val math = mutableListOf<MathPiece>()
    var remainingPixels = 3000000L
    val prepared = tokens.replace(text.replace('\uE000', ' ').replace('\uE001', ' ')) { match ->
        val token = match.value
        val block = token.startsWith("$$") || token.startsWith("\\[")
        val inline = token.startsWith("$") || token.startsWith("\\(") && token.endsWith("\\)")
        if ((!block && !inline) || token.length < (if (block || token.startsWith("\\")) 4 else 3)) return@replace token
        val width = if (block || token.startsWith("\\")) 2 else 1
        val tex = token.substring(width, token.length - width).trim()
        val bitmap = if (remainingPixels > 0 && renderableFormula(tex)) formulaImage(tex, pixels, color) else null
        if (bitmap != null) remainingPixels -= bitmap.width.toLong() * bitmap.height
        val index = math.size
        math.add(MathPiece(tex, block, bitmap))
        val placeholder = "\uE000" + index + "\uE001"
        if (block) "\n\n" + placeholder + "\n\n" else placeholder
    }
    return Document(parser.parse(prepared), math)
}

private fun Node.children() = generateSequence(firstChild) { it.next }

@Composable
internal fun ReadingMarkdown(markdown: String, size: Int) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val pixels = with(LocalDensity.current) { size.sp.toPx() }
    val document by produceState<Document?>(null, markdown, pixels, color) {
        value = withContext(Dispatchers.Default) { parseReading(markdown, pixels, color) }
    }
    val doc = document
    if (doc == null) Text(markdown, fontSize = size.sp)
    else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        doc.root.children().forEach { ReadingBlock(it, doc, size) }
    }
}

@Composable
private fun ReadingBlock(node: Node, doc: Document, size: Int) {
    when (node) {
        is FencedCodeBlock -> CodeBlock(node.literal, size)
        is IndentedCodeBlock -> CodeBlock(node.literal, size)
        is OrderedList, is BulletList -> {
            val start = (node as? OrderedList)?.markerStartNumber ?: 1
            node.children().forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (node is OrderedList) (start + index).toString() + "." else "•", fontSize = size.sp)
                    Column { item.children().forEach { ReadingBlock(it, doc, size) } }
                }
            }
        }
        is TableBlock -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            node.children().flatMap { it.children() }.forEach { row ->
                Row { row.children().forEach { cell ->
                    Box(Modifier.width(132.dp).padding(5.dp)) { InlineReading(cell, doc, size, cell.parent.parent is TableHead) }
                } }
                HorizontalDivider()
            }
        }
        is BlockQuote -> Column(Modifier.padding(start = 12.dp)) { node.children().forEach { ReadingBlock(it, doc, size) } }
        is ThematicBreak -> HorizontalDivider()
        is HtmlBlock -> Text(node.literal, fontSize = size.sp)
        else -> {
            val only = (node.firstChild as? MarkdownText)?.takeIf { it.next == null }?.literal.orEmpty()
            val formula = marker.matchEntire(only)?.groupValues?.get(1)?.toIntOrNull()?.let(doc.math::get)
            if (formula?.block == true && formula.bitmap != null) {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    val image = formula.bitmap
                    with(LocalDensity.current) { Image(image, formula.text, Modifier.size(image.width.toDp(), image.height.toDp())) }
                }
            } else InlineReading(node, doc, if (node is Heading) size + 2 else size, node is Heading)
        }
    }
}

@Composable
private fun CodeBlock(code: String, size: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(code.trimEnd('\n'), Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            fontFamily = FontFamily.Monospace, fontSize = size.sp, softWrap = false)
    }
}

@Composable
private fun InlineReading(node: Node, doc: Document, size: Int, bold: Boolean) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val limit = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val content = mutableMapOf<String, InlineTextContent>()
        val string = buildAnnotatedString {
            fun addText(text: String) {
                var last = 0
                marker.findAll(text).forEach { hit ->
                    append(text.substring(last, hit.range.first))
                    val id = hit.groupValues[1]
                    val math = doc.math[id.toInt()]
                    val bitmap = math.bitmap
                    if (bitmap == null) append(math.text) else {
                        val scale = minOf(1f, limit / bitmap.width)
                        val box = with(density) { Placeholder((bitmap.width * scale).toSp(), (bitmap.height * scale).toSp(), PlaceholderVerticalAlign.TextCenter) }
                        appendInlineContent(id, math.text.ifBlank { "公式" })
                        content[id] = InlineTextContent(box) { Image(bitmap, math.text, Modifier.fillMaxSize()) }
                    }
                    last = hit.range.last + 1
                }
                append(text.substring(last))
            }
            fun visit(child: Node) {
                when (child) {
                    is MarkdownText -> addText(child.literal)
                    is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(child.literal) }
                    is SoftLineBreak, is HardLineBreak -> append("\n")
                    is HtmlInline -> append(child.literal)
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { child.children().forEach(::visit) }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { child.children().forEach(::visit) }
                    else -> child.children().forEach(::visit)
                }
            }
            node.children().forEach(::visit)
        }
        Text(string, inlineContent = content, fontSize = size.sp, lineHeight = (size + 9).sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
