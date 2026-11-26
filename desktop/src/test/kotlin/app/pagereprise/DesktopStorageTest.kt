package app.pagereprise

import app.pagereprise.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class DesktopStorageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun portableBackupRetainsBothPicturesHistoryAndOwnershipAcrossInstallations() = runBlocking {
        val source = StudyPlatform(temp.newFolder("source"))
        val target = StudyPlatform(temp.newFolder("destination"))
        val account = "project:account"
        val zip = temp.newFile("backup.zip")
        try {
            val media = source.media(account)
            val transparent = BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB)
            transparent.setRGB(30, 20, 0xff007744.toInt())
            val bytes = ByteArrayOutputStream().apply { ImageIO.write(transparent, "png", this) }.toByteArray()
            val question = media.import(InputFile { bytes.inputStream() })
            val answer = media.crop(question, 0f, .5f, 1f, 1f)
            assertEquals(20, ImageIO.read(media.file(answer)).height)
            assertEquals(0xFFFFFF, ImageIO.read(media.file(question)).getRGB(0, 0) and 0xFFFFFF)
            val at = System.currentTimeMillis() - 4 * 86_400_000L
            val created = Event.create("math", Body(title = "积分", pictures = listOf(question), answers = listOf(answer)), at)
            val missed = Library.card(listOf(created))!!.answer(false, at + 2 * 86_400_000L)
            val events = listOf(created, missed)
            source.store.merge(account, events, true)
            writeAtomically(zip) { Backup.write(it, account, events) { hash -> media.file(hash).inputStream() } }
            assertTrue(runCatching { Backup.preview(zip.inputStream(), "other", target.cacheDir) }.isFailure)
            Backup.preview(zip.inputStream(), account, target.cacheDir).use { restored ->
                repeat(2) { target.store.importWithImages(account, restored.index.events) { File(restored.directory, it).inputStream() } }
            }
            val received = target.store.dao.entries(account).map { Codec.decode(it.payload) }
            assertEquals(Library.card(events), Library.card(received))
            assertArrayEquals(media.file(question).readBytes(), target.media(account).file(question).readBytes())
            assertTrue(target.store.dao.entries("other").isEmpty())
        } finally { source.store.close(); target.store.close() }
        Store(target.directory).use { reopened -> assertEquals(2, reopened.dao.entries(account).size) }
    }

    @Test fun pastDeadlineAndRetriesCannotResurrectTheCardOrItsPictures() = runBlocking {
        val dir = temp.newFolder()
        val guest = "local:" + newId()
        val media = Media(dir, guest)
        val raw = "image bytes".toByteArray()
        val hash = digest(raw)
        media.accept(hash, raw.inputStream())
        val created = Event.create("english", Body(pictures = listOf(hash)))
        val trashed = Library.card(listOf(created))!!.trash()
        val end = trashed.at + TRASH_LIFETIME
        Store(dir).use { store ->
            store.merge(guest, listOf(created, trashed), true)
            assertEquals(end, store.maintain(guest, end - 1))
            assertTrue(media.file(hash).exists())
            store.maintain(guest, end)
            store.importWithImages(guest, listOf(created)) { error("Expired images should never be requested") }
            assertEquals(Kind.PURGE, Codec.decode(store.dao.item(guest, created.id).single().payload).kind)
            assertFalse(media.file(hash).exists())
        }
    }

    @Test fun concurrentWritesAndFailedRestoreLeaveConsistentData() = runBlocking {
        val owner = "local:" + newId()
        val missing = digest("missing".toByteArray())
        val broken = Event.create("math", Body(pictures = listOf(missing)))
        val item = Event.create("cs", Body(title = "调度"))
        Store(temp.newFolder()).use { store ->
            assertTrue(runCatching { store.importWithImages(owner, listOf(broken)) { error("Interrupted") } }.isFailure)
            assertTrue(store.dao.entries(owner).isEmpty())
            coroutineScope { repeat(8) { launch(Dispatchers.Default) { store.merge(owner, listOf(item), true) } } }
            assertEquals(1, store.dao.entries(owner).size)
            store.dao.sent(owner, listOf(item.id))
            assertFalse(store.dao.entries(owner).single().pending)
        }
    }

    @Test fun desktopSessionSurvivesRestartUnderCurrentWindowsUser() {
        val path = File(temp.newFolder(), "session.dpapi")
        val session = Session("https://example.test", "public-key", "test@example.test", newId(), "sample-access", "sample-refresh", 123)
        Vault(path).write(session)
        assertFalse(String(path.readBytes(), Charsets.ISO_8859_1).contains("sample-refresh"))
        assertEquals(session, Vault(path).read())
        Vault(path).write(null)
        assertNull(Vault(path).read())
    }
}
