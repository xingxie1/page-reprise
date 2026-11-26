package app.pagereprise.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    @Test fun restoresOnlyOwnArchiveAndPreservesPhotoAndMoves() {
        val photo = "photo bytes".toByteArray()
        val hash = digest(photo)
        val first = Event.create("cs", Body(pictures = listOf(hash)), 1800000000000L)
        val move = Library.card(listOf(first))!!.move("math")
        val events = listOf(first, move)
        val output = ByteArrayOutputStream()
        Backup.write(output, "owner", events) { photo.inputStream() }
        val temp = Files.createTempDirectory("reprise-backup").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { Backup.preview(output.toByteArray().inputStream(), "friend", temp) }
            Backup.preview(output.toByteArray().inputStream(), "owner", temp).use { staged ->
                assertEquals(events, staged.index.events)
                assertEquals("math", Library.card(staged.index.events + events)!!.subject)
                assertArrayEquals(photo, staged.directory.resolve(hash).readBytes())
            }
            assertTrue(temp.list().orEmpty().isEmpty())
        } finally { temp.deleteRecursively() }
    }

    @Test fun rejectsUnexpectedNamesAndDamagedImages() {
        val temp = Files.createTempDirectory("reprise-invalid").toFile()
        try {
            for (name in listOf("../unwanted", "images/" + "a".repeat(64))) {
                val bytes = ByteArrayOutputStream()
                ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(1) }
                assertTrue(runCatching { Backup.preview(bytes.toByteArray().inputStream(), "owner", temp) }.isFailure)
                assertTrue(temp.list().orEmpty().isEmpty())
            }
        } finally { temp.deleteRecursively() }
    }
}
