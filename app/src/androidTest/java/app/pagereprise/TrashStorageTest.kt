package app.pagereprise

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pagereprise.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrashStorageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun expiryErasesOnlyUnreferencedContentAndRejectsOldBackups() = runBlocking {
        val owner = "local:" + newId()
        val store = Store(context)
        val media = Media(context, owner)
        fun photo(text: String): String {
            val bytes = text.toByteArray()
            return digest(bytes).also { media.accept(it, bytes.inputStream()) }
        }
        try {
            val only = photo("deleted")
            val shared = photo("shared")
            val draft = photo("draft")
            val original = Event.create("math", Body(pictures = listOf(only, shared), answers = listOf(draft)))
            val keep = Event.create("cs", Body(pictures = listOf(shared)))
            val mark = Library.card(listOf(original))!!.trash()
            val end = mark.at + TRASH_LIFETIME
            store.merge(owner, listOf(original, keep, mark), true)
            store.dao.saveDraft(Draft(owner, newId(), "", Codec.json.toJson(Body(pictures = listOf(draft))), mark.at))
            assertEquals(end, store.maintain(owner, end - 1))
            assertTrue(media.file(only).isFile)
            store.maintain(owner, end)
            assertEquals(Kind.PURGE, Codec.decode(store.dao.item(owner, original.id).single().payload).kind)
            assertFalse(media.file(only).exists())
            assertTrue(media.file(shared).isFile)
            assertTrue(media.file(draft).isFile)
            store.importWithImages(owner, listOf(original)) { hash -> media.file(hash).inputStream() }
            assertNull(Library.card(store.dao.item(owner, original.id).map { Codec.decode(it.payload) }))
            assertFalse(media.file(only).exists())
        } finally { store.db.close() }
    }

    @Test fun anExistingVersionOneNotebookMigratesWithoutLosingItsEntries() = runBlocking {
        val name = "migration-" + newId() + ".db"
        val card = Event.create("math", Body(title = "已有题目"))
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE operations (owner TEXT NOT NULL,eventId TEXT NOT NULL,cardId TEXT NOT NULL,payload TEXT NOT NULL,pending INTEGER NOT NULL,PRIMARY KEY(owner,eventId))")
                db.execSQL("CREATE INDEX index_operations_owner_cardId ON operations(owner,cardId)")
                db.execSQL("CREATE TABLE drafts (owner TEXT NOT NULL,draftId TEXT NOT NULL,subject TEXT NOT NULL,bodyJson TEXT NOT NULL,savedAt INTEGER NOT NULL,PRIMARY KEY(owner,draftId))")
                db.execSQL("INSERT INTO operations VALUES(?,?,?,?,1)", arrayOf("owner",card.id,card.id,Codec.encode(card)))
                db.version = 1
            }
            val upgraded = Room.databaseBuilder(context, StudyDatabase::class.java, name).build()
            try {
                assertEquals(card, Codec.decode(upgraded.journal().entries("owner").single().payload))
                assertTrue(upgraded.journal().discardedImages("owner").isEmpty())
            } finally { upgraded.close() }
        } finally { context.deleteDatabase(name) }
    }
}
