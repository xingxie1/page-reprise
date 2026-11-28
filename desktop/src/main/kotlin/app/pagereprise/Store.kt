package app.pagereprise

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.sqlite.SQLiteConfig
import java.io.Closeable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class Store(private val directory: File) : JournalStore({ Media(directory, it) }), Closeable {
    private val connection: Connection
    private val mutex = Mutex()
    private class Transaction(val store: Store) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Transaction>
    }

    init {
        directory.mkdirs()
        Class.forName("org.sqlite.JDBC")
        val config = SQLiteConfig().apply {
            setBusyTimeout(10000)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + File(directory, "circulation.db").absolutePath, config.toProperties())
        try {
            connection.createStatement().use { sql ->
                val version = sql.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                require(version in listOf(0, 2)) { "请使用更新的循页打开这个题库" }
                if (version == 0) {
                    connection.autoCommit = false
                    try {
                        sql.execute("CREATE TABLE IF NOT EXISTS operations (owner TEXT NOT NULL, eventId TEXT NOT NULL, cardId TEXT NOT NULL, payload TEXT NOT NULL, pending INTEGER NOT NULL, PRIMARY KEY(owner,eventId))")
                        sql.execute("CREATE INDEX IF NOT EXISTS operations_card ON operations(owner,cardId)")
                        sql.execute("CREATE TABLE IF NOT EXISTS drafts (owner TEXT NOT NULL, draftId TEXT NOT NULL, subject TEXT NOT NULL, bodyJson TEXT NOT NULL, savedAt INTEGER NOT NULL, PRIMARY KEY(owner,draftId))")
                        sql.execute("CREATE TABLE IF NOT EXISTS discarded_images (owner TEXT NOT NULL, hash TEXT NOT NULL, PRIMARY KEY(owner,hash))")
                        sql.execute("PRAGMA user_version=2")
                        connection.commit()
                    } catch (failure: Throwable) {
                        connection.rollback()
                        throw failure
                    } finally { connection.autoCommit = true }
                }
            }
        } catch (failure: Throwable) { connection.close(); throw failure }
    }

    override suspend fun <T> transaction(block: suspend () -> T): T {
        if (coroutineContext[Transaction]?.store === this) return block()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                connection.autoCommit = false
                try {
                    val result = withContext(Transaction(this@Store)) { block() }
                    connection.commit()
                    result
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                } finally { connection.autoCommit = true }
            }
        }
    }

    private suspend fun <T> access(block: Connection.() -> T): T {
        if (coroutineContext[Transaction]?.store === this) return connection.block()
        return withContext(Dispatchers.IO) { mutex.withLock { connection.block() } }
    }

    private suspend fun update(sql: String, vararg args: Any): Int = access {
        prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private suspend fun <T> rows(sql: String, vararg args: String, read: (ResultSet) -> T): List<T> = access {
        prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(read(result)) } }
        }
    }

    private fun entry(r: ResultSet) = Entry(r.getString("owner"), r.getString("eventId"), r.getString("cardId"), r.getString("payload"), r.getBoolean("pending"))

    override val dao = object : StudyJournal {
        override suspend fun entries(owner: String) = rows("SELECT * FROM operations WHERE owner=? ORDER BY eventId", owner, read = ::entry)
        override suspend fun item(owner: String, card: String) = rows("SELECT * FROM operations WHERE owner=? AND cardId=?", owner, card, read = ::entry)
        override suspend fun existing(owner: String, id: String) = rows("SELECT * FROM operations WHERE owner=? AND eventId=?", owner, id, read = ::entry).singleOrNull()
        override suspend fun put(entry: Entry): Long {
            val count = update("INSERT OR IGNORE INTO operations VALUES(?,?,?,?,?)", entry.owner, entry.eventId, entry.cardId, entry.payload, entry.pending)
            return if (count == 0) -1L else 1L
        }
        override suspend fun updatePayload(owner: String, id: String, payload: String) {
            update("UPDATE operations SET payload=? WHERE owner=? AND eventId=?", payload, owner, id)
        }
        override suspend fun sent(owner: String, ids: List<String>) {
            if (ids.isEmpty()) return
            val placeholders = ids.joinToString(",") { "?" }
            update("UPDATE operations SET pending=0 WHERE owner=? AND eventId IN (" + placeholders + ")", owner, *ids.toTypedArray())
        }
        override suspend fun drafts(owner: String) = rows("SELECT * FROM drafts WHERE owner=? ORDER BY savedAt DESC", owner) {
            Draft(it.getString("owner"), it.getString("draftId"), it.getString("subject"), it.getString("bodyJson"), it.getLong("savedAt"))
        }
        override suspend fun saveDraft(draft: Draft) {
            update("INSERT OR REPLACE INTO drafts VALUES(?,?,?,?,?)", draft.owner, draft.draftId, draft.subject, draft.bodyJson, draft.savedAt)
        }
        override suspend fun discard(owner: String, id: String) { update("DELETE FROM drafts WHERE owner=? AND draftId=?", owner, id) }
        override suspend fun eraseCard(owner: String, card: String) { update("DELETE FROM operations WHERE owner=? AND cardId=?", owner, card) }
        override suspend fun queueImages(images: List<DiscardedImage>) {
            for (image in images) update("INSERT OR IGNORE INTO discarded_images VALUES(?,?)", image.owner, image.hash)
        }
        override suspend fun discardedImages(owner: String) = rows("SELECT hash FROM discarded_images WHERE owner=?", owner) { it.getString(1) }
        override suspend fun finishImage(owner: String, hash: String) { update("DELETE FROM discarded_images WHERE owner=? AND hash=?", owner, hash) }
    }
    override fun close() = connection.close()
}
