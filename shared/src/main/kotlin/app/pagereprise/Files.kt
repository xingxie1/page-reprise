package app.pagereprise

import java.io.InputStream
import java.io.OutputStream

fun interface InputFile { fun open(): InputStream }
fun interface OutputFile { fun write(content: (OutputStream) -> Unit) }
