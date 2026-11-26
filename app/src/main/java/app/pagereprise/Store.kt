package app.pagereprise

import android.content.Context
import androidx.room.Dao
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import app.pagereprise.core.*

@Entity(tableName = "operations", primaryKeys = ["owner", "eventId"], indices = [Index(value = ["owner", "cardId"])])
data class Entry(val owner: String, val eventId: String, val cardId: String, val payload: String, val pending: Boolean)
@Entity(tableName = "drafts", primaryKeys = ["owner", "draftId"])
data class Draft(val owner: String, val draftId: String, val subject: String, val bodyJson: String, val savedAt: Long)
@Entity(tableName = "discarded_images", primaryKeys = ["owner", "hash"])
data class DiscardedImage(val owner: String, val hash: String)

@Dao
interface Journal {
    @Query("SELECT * FROM operations WHERE owner = :owner ORDER BY eventId")
    suspend fun entries(owner: String): List<Entry>
    @Query("SELECT * FROM operations WHERE owner = :owner AND cardId = :card")
    suspend fun item(owner: String, card: String): List<Entry>
    @Query("SELECT * FROM operations WHERE owner = :owner AND eventId = :id")
    suspend fun existing(owner: String, id: String): Entry?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun put(entry: Entry): Long
    @Query("UPDATE operations SET pending = 0 WHERE owner = :owner AND eventId IN (:ids)")
    suspend fun sent(owner: String, ids: List<String>)
    @Query("SELECT * FROM drafts WHERE owner = :owner ORDER BY savedAt DESC")
    suspend fun drafts(owner: String): List<Draft>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: Draft)
    @Query("DELETE FROM drafts WHERE owner = :owner AND draftId = :id")
    suspend fun discard(owner: String, id: String)
    @Query("DELETE FROM operations WHERE owner = :owner AND cardId = :card")
    suspend fun eraseCard(owner: String, card: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun queueImages(images: List<DiscardedImage>)
    @Query("SELECT hash FROM discarded_images WHERE owner = :owner")
    suspend fun discardedImages(owner: String): List<String>
    @Query("DELETE FROM discarded_images WHERE owner = :owner AND hash = :hash")
    suspend fun finishImage(owner: String, hash: String)
}

@Database(entities = [Entry::class, Draft::class, DiscardedImage::class], version = 2, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)])
abstract class StudyDatabase : RoomDatabase() {
    abstract fun journal(): Journal
}

class Store(private val context: Context) {
    val db = Room.databaseBuilder(context, StudyDatabase::class.java, "circulation.db").build()
    val dao = db.journal()

    suspend fun merge(owner: String, events: List<Event>, pending: Boolean) = db.withTransaction {
        for ((id, group) in events.groupBy { it.card }) {
            val marker = group.firstOrNull { it.kind == Kind.PURGE }
            if (dao.existing(owner, id)?.let { Codec.decode(it.payload).kind } == Kind.PURGE) continue
            if (marker != null) {
                val payload = Codec.encode(marker)
                val images = dao.item(owner, id).map { Codec.decode(it.payload) }.images()
                dao.queueImages(images.map { DiscardedImage(owner, it) })
                dao.eraseCard(owner, id)
                dao.put(Entry(owner, id, id, payload, false))
                continue
            }
            for (event in group) {
                val encoded = Codec.encode(event)
                val added = dao.put(Entry(owner, event.id, event.card, encoded, pending))
                if (added == -1L) {
                    val existing = requireNotNull(dao.existing(owner, event.id))
                    require(Codec.decode(existing.payload) == event) { "同一操作标识内容不一致，已停止合并" }
                }
            }
        }
    }

    suspend fun maintain(owner: String, now: Long = System.currentTimeMillis()): Long? = db.withTransaction {
        var next: Long? = null
        if (owner.startsWith("local:")) {
            val entries = dao.entries(owner).map { Codec.decode(it.payload) }
            for (card in Library.project(entries)) {
                val deadline = card.expiresAt ?: continue
                if (now >= deadline) merge(owner, listOf(Event(id = card.id, card = card.id, at = deadline, kind = Kind.PURGE)), false)
                else next = minOf(next ?: deadline, deadline)
            }
        }
        val candidates = dao.discardedImages(owner)
        if (candidates.isNotEmpty()) {
            val retained = dao.entries(owner).map { Codec.decode(it.payload) }.images().toMutableSet()
            dao.drafts(owner).forEach {
                val body = Codec.json.fromJson(it.bodyJson, Body::class.java)
                retained.addAll(body.pictures + body.answers)
            }
            val files = Media(context, owner)
            for (hash in candidates) {
                if (hash in retained || files.removeIfUnused(hash)) dao.finishImage(owner, hash)
            }
        }
        next
    }

    suspend fun importWithImages(owner: String, events: List<Event>, source: (String) -> java.io.InputStream) = db.withTransaction {
        merge(owner, events, true)
        val retained = dao.entries(owner).map { Codec.decode(it.payload) }.images()
        val files = Media(context, owner)
        for (hash in events.images().intersect(retained)) {
            if (!files.file(hash).exists()) source(hash).use { files.accept(hash, it) }
        }
    }

    suspend fun act(owner: String, card: String, allowTrashed: Boolean = false, make: (Card) -> Event): Event = db.withTransaction {
        val state = Library.card(dao.item(owner, card).map { Codec.decode(it.payload) }) ?: error("找不到这道题")
        require(allowTrashed || !state.trashed) { "这道题已移入回收站" }
        val event = make(state).checked()
        merge(owner, listOf(event), true)
        event
    }
}
