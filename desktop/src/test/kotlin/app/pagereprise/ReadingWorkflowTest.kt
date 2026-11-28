package app.pagereprise

import app.pagereprise.core.*
import com.google.gson.JsonParser
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingWorkflowTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun requestUsesOneImageRolePromptAndCurrentOfficialModel() {
        val file = temp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var status = 200
        var reason = "stop"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://api.deepseek.com/chat/completions", request.url.toString())
            assertEquals("Bearer fixture", request.header("Authorization"))
            val payload = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals("deepseek-flash", payload["model"].asString)
            val messages = payload.getAsJsonArray("messages")
            assertEquals(2, messages.size())
            assertTrue(messages[0].asJsonObject["content"].asString.contains("〔待核对〕"))
            val parts = messages[1].asJsonObject.getAsJsonArray("content")
            assertEquals(2, parts.size())
            assertTrue(parts[0].asJsonObject["text"].asString.contains("参考答案"))
            val image = parts[1].asJsonObject.getAsJsonObject("image_url")
            assertEquals("data:image/jpeg;base64,AQID", image["url"].asString)
            assertEquals("original", image["detail"].asString)
            val choices = listOf(mapOf("finish_reason" to reason, "message" to mapOf("content" to "阅读稿", "reasoning_content" to "discard")))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(Codec.json.toJson(mapOf("choices" to choices)).toResponseBody()).build()
        }.build()
        fun read() = runBlocking { DeepSeekReader(client).transcribe("fixture", file, true, "math") }
        assertEquals("阅读稿", read())
        reason = "length"
        assertTrue(assertThrows(IllegalStateException::class.java) { read() }.message!!.contains("完整"))
        status = 402
        assertTrue(assertThrows(IllegalStateException::class.java) { read() }.message!!.contains("余额"))
        assertFalse(ReadingPrompts.page(false, "cs-ds").contains("誊写本图已有的参考答案"))
        assertTrue(renderableFormula("""\int_0^1 \frac{x}{2}\,dx"""))
        assertNotNull(formulaImage("""\int_0^1 \frac{x}{2}\,dx""", 18f, -1))
    }

    @Test fun failedOrCancelledPagesStayEditableAndNeverRequeueOnOpen() = runBlocking {
        val platform = StudyPlatform(temp.newFolder("skip"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var calls = 0
        val reader = ImageTranscriber { _, _, _, _ ->
            calls++
            if (calls == 1) throw IllegalStateException("余额不足")
            awaitCancellation()
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(12, 10, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        try {
            withContext(Dispatchers.Main) {
                val model = StudyModel(platform, scope, reader)
                suspend fun until(ok: () -> Boolean) = withTimeout(10000) { while (!ok()) delay(10) }
                until { model.ready }
                model.newEditor("english")
                model.importImages(listOf(InputFile(bytes::inputStream)), false)
                until { !model.busy }
                val target = ReadingPage(false, model.editor.body.pictures.single())
                assertEquals(0, calls)
                model.setReadingKey("synthetic-device-key")
                until { model.readingError(target) == "余额不足" }
                model.recognize(target)
                until { calls == 2 }
                model.saveCard()
                model.stopReading()
                until { !model.busy && model.cards.size == 1 }
                val card = model.cards.single()
                assertTrue(model.media.file(target.hash).isFile)
                assertNull(card.body.reading(false, target.hash))
                model.editCard(card)
                assertEquals(2, calls)
                model.recognize(target)
                until { calls == 3 }
                model.correctReading(target, "手工转写")
                model.saveCard()
                until { !model.busy }
                assertEquals("手工转写", model.cards.single().body.reading(false, target.hash))
                model.close()
            }
        } finally { scope.cancel(); platform.store.close() }
    }

    @Test fun personalKeyOnlyTranscribesImportedPagesAndSaveWaitsForBothSides() = runBlocking {
        val platform = StudyPlatform(temp.newFolder("record"))
        val other = StudyPlatform(temp.newFolder("read"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val first = CompletableDeferred<Unit>()
        val roles = mutableListOf<Boolean>()
        val fakeKey = "local-test-" + newId()
        val reader = ImageTranscriber { key, image, answer, _ ->
            assertEquals(fakeKey, key)
            assertTrue(image.isFile)
            roles.add(answer)
            first.await()
            if (answer) "答案原文" else "题面原文"
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val image = InputFile(bytes::inputStream)
        try {
            withContext(Dispatchers.Main) {
                val model = StudyModel(platform, scope, reader)
                suspend fun until(ok: () -> Boolean) = withTimeout(10000) { while (!ok()) delay(15) }
                until { model.ready }
                model.setReadingKey(fakeKey)
                assertFalse(platform.directory.walk().filter { it.extension == "dpapi" }.single().readBytes().toString(Charsets.ISO_8859_1).contains(fakeKey))
                assertNull(platform.vault.readingKey("another-account"))
                model.newEditor("math")
                model.importImages(listOf(image, image), false)
                until { !model.busy && roles == listOf(false) }
                model.shared(listOf(image))
                until { !model.busy && model.readingCount == 2 }
                model.saveCard()
                assertTrue(model.busy)
                first.complete(Unit)
                until { !model.busy && model.cards.size == 1 }
                val card = model.cards.single()
                val hash = card.body.pictures.first()
                assertEquals(listOf(false, true), roles)
                assertEquals("题面原文", card.body.reading(false, hash))
                assertEquals("答案原文", card.body.reading(true, hash))
                val event = Codec.decode(platform.store.dao.entries(model.owner).single().payload)
                assertFalse(Codec.encode(event).contains(fakeKey))
                val older = event.copy(body = event.body!!.copy(readings = null))
                other.store.merge(model.owner, listOf(older), true)
                other.store.merge(model.owner, listOf(event, older, event), false)
                val cached = other.store.dao.entries(model.owner).single()
                assertTrue(cached.pending)
                assertEquals(event, Codec.decode(cached.payload))
                assertNull(other.vault.readingKey(model.owner))
                model.editCard(card)
                assertEquals(0, model.readingCount)
                assertTrue(model.correctReading(ReadingPage(false, hash), "人工校对"))
                model.saveCard()
                until { !model.busy }
                assertEquals("人工校对", model.cards.single().body.reading(false, hash))
                model.setReadingKey(null)
                assertNull(platform.vault.readingKey(model.owner))
                model.close()
            }
        } finally { scope.cancel(); platform.store.close(); other.store.close() }
    }
}
