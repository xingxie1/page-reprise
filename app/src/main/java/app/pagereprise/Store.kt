package app.pagereprise

import android.content.Context
import androidx.room.Dao
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
}

@Database(entities = [Entry::class, Draft::class], version = 1, exportSchema = true)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun journal(): Journal
}

class Store(context: Context) {
    val db = Room.databaseBuilder(context, StudyDatabase::class.java, "circulation.db").build()
    val dao = db.journal()

    suspend fun merge(owner: String, events: List<Event>, pending: Boolean) = db.withTransaction {
        events.forEach { event ->
            val encoded = Codec.encode(event)
            val added = dao.put(Entry(owner, event.id, event.card, encoded, pending))
            if (added == -1L) {
                val existing = requireNotNull(dao.existing(owner, event.id))
                require(Codec.decode(existing.payload) == event) { "同一操作标识内容不一致，已停止合并" }
            }
        }
    }

    suspend fun act(owner: String, card: String, make: (Card) -> Event): Event = db.withTransaction {
        val state = Library.card(dao.item(owner, card).map { Codec.decode(it.payload) }) ?: error("找不到这道题")
        val event = make(state).checked()
        merge(owner, listOf(event), true)
        event
    }
}
