package app.pagereprise

import android.content.Context
import androidx.room.Dao
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Dao
interface Journal : StudyJournal {
    @Query("SELECT * FROM operations WHERE owner = :owner ORDER BY eventId")
    override suspend fun entries(owner: String): List<Entry>
    @Query("SELECT * FROM operations WHERE owner = :owner AND cardId = :card")
    override suspend fun item(owner: String, card: String): List<Entry>
    @Query("SELECT * FROM operations WHERE owner = :owner AND eventId = :id")
    override suspend fun existing(owner: String, id: String): Entry?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    override suspend fun put(entry: Entry): Long
    @Query("UPDATE operations SET pending = 0 WHERE owner = :owner AND eventId IN (:ids)")
    override suspend fun sent(owner: String, ids: List<String>)
    @Query("SELECT * FROM drafts WHERE owner = :owner ORDER BY savedAt DESC")
    override suspend fun drafts(owner: String): List<Draft>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun saveDraft(draft: Draft)
    @Query("DELETE FROM drafts WHERE owner = :owner AND draftId = :id")
    override suspend fun discard(owner: String, id: String)
    @Query("DELETE FROM operations WHERE owner = :owner AND cardId = :card")
    override suspend fun eraseCard(owner: String, card: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    override suspend fun queueImages(images: List<DiscardedImage>)
    @Query("SELECT hash FROM discarded_images WHERE owner = :owner")
    override suspend fun discardedImages(owner: String): List<String>
    @Query("DELETE FROM discarded_images WHERE owner = :owner AND hash = :hash")
    override suspend fun finishImage(owner: String, hash: String)
}

@Database(entities = [Entry::class, Draft::class, DiscardedImage::class], version = 2, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)])
abstract class StudyDatabase : RoomDatabase() {
    abstract fun journal(): Journal
}

class Store(context: Context) : JournalStore({ owner -> Media(context, owner) }) {
    val db = Room.databaseBuilder(context, StudyDatabase::class.java, "circulation.db").build()
    override val dao = db.journal()
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
}
