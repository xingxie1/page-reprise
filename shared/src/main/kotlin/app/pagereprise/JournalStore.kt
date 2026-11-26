package app.pagereprise

import androidx.room.Entity
import androidx.room.Index
import app.pagereprise.core.*

@Entity(tableName = "operations", primaryKeys = ["owner", "eventId"], indices = [Index(value = ["owner", "cardId"])])
data class Entry(val owner: String, val eventId: String, val cardId: String, val payload: String, val pending: Boolean)
@Entity(tableName = "drafts", primaryKeys = ["owner", "draftId"])
data class Draft(val owner: String, val draftId: String, val subject: String, val bodyJson: String, val savedAt: Long)
@Entity(tableName = "discarded_images", primaryKeys = ["owner", "hash"])
data class DiscardedImage(val owner: String, val hash: String)

interface StudyJournal {
    suspend fun entries(owner: String): List<Entry>
    suspend fun item(owner: String, card: String): List<Entry>
    suspend fun existing(owner: String, id: String): Entry?
    suspend fun put(entry: Entry): Long
    suspend fun sent(owner: String, ids: List<String>)
    suspend fun drafts(owner: String): List<Draft>
    suspend fun saveDraft(draft: Draft)
    suspend fun discard(owner: String, id: String)
    suspend fun eraseCard(owner: String, card: String)
    suspend fun queueImages(images: List<DiscardedImage>)
    suspend fun discardedImages(owner: String): List<String>
    suspend fun finishImage(owner: String, hash: String)
}

abstract class JournalStore(private val mediaFor: (String) -> MediaFiles) {
    abstract val dao: StudyJournal
    abstract suspend fun <T> transaction(block: suspend () -> T): T

    suspend fun merge(owner: String, events: List<Event>, pending: Boolean) = transaction {
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

    suspend fun maintain(owner: String, now: Long = System.currentTimeMillis()): Long? = transaction {
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
            val retained = dao.entries(owner).map { Codec.decode(it.payload) }.images()
            val drafts = dao.drafts(owner).flatMap {
                val body = Codec.json.fromJson(it.bodyJson, Body::class.java)
                body.pictures + body.answers
            }.toSet()
            val files = mediaFor(owner)
            for (hash in candidates) {
                if (hash in retained || (hash !in drafts && files.removeIfUnused(hash))) dao.finishImage(owner, hash)
            }
        }
        next
    }

    suspend fun importWithImages(owner: String, events: List<Event>, source: (String) -> java.io.InputStream) = transaction {
        merge(owner, events, true)
        val retained = dao.entries(owner).map { Codec.decode(it.payload) }.images()
        val files = mediaFor(owner)
        for (hash in events.images().intersect(retained)) {
            if (!files.file(hash).exists()) source(hash).use { files.accept(hash, it) }
        }
    }

    suspend fun act(owner: String, card: String, allowTrashed: Boolean = false, make: (Card) -> Event): Event = transaction {
        val state = Library.card(dao.item(owner, card).map { Codec.decode(it.payload) }) ?: error("找不到这道题")
        require(allowTrashed || !state.trashed) { "这道题已移入回收站" }
        val event = make(state).checked()
        merge(owner, listOf(event), true)
        event
    }
}
