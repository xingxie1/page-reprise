package app.pagereprise

import app.pagereprise.core.Backup
import app.pagereprise.core.Codec
import app.pagereprise.core.Subjects
import app.pagereprise.core.validTopicText
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Transcription(val markdown: String, val knowledgePoint: String? = null)

fun interface ImageTranscriber {
    suspend fun transcribe(key: String, image: File, answer: Boolean, subject: String): Transcription
}

internal object ReadingPrompts {
    private val document by lazy {
        requireNotNull(javaClass.getResourceAsStream("/reading/transcription.md")).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    val system get() = document.substringAfter("## 通用规则").substringBefore("## 题干图").trim()
    fun page(answer: Boolean, subject: String): String {
        val role = if (answer) document.substringAfter("## 答案图") else document.substringAfter("## 题干图").substringBefore("## 答案图")
        return role.trim() + (Subjects.all[subject]?.let { "\n本科目为" + it + "。" } ?: "")
    }
}

class DeepSeekReader internal constructor(private val http: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .callTimeout(120, TimeUnit.SECONDS).readTimeout(100, TimeUnit.SECONDS).build()) : ImageTranscriber {
    override suspend fun transcribe(key: String, image: File, answer: Boolean, subject: String): Transcription = withContext(Dispatchers.IO) {
        require(image.isFile && image.length() in 1L..Backup.imageLimit.toLong()) { "题图暂不可用，请同步原图或裁切后重试" }
        val data = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image.readBytes())
        val content = listOf(mapOf("type" to "text", "text" to ReadingPrompts.page(answer, subject)),
            mapOf("type" to "image_url", "image_url" to mapOf("url" to data, "detail" to "original")))
        val messages = listOf(mapOf("role" to "system", "content" to ReadingPrompts.system), mapOf("role" to "user", "content" to content))
        val json = Codec.json.toJson(mapOf("model" to "deepseek-flash", "messages" to messages, "stream" to false,
            "thinking" to mapOf("type" to "disabled"), "response_format" to mapOf("type" to "json_object"), "max_tokens" to 8192))
        val request = Request.Builder().url("https://api.deepseek.com/chat/completions").header("Authorization", "Bearer " + key)
            .post(json.toRequestBody("application/json".toMediaType())).build()
        suspendCancellableCoroutine { result ->
            val call = http.newCall(request)
            result.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    result.resumeWithException(IOException("未能连接 DeepSeek，请检查网络后重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            check(it.isSuccessful) { when (it.code) {
                                401, 403 -> "DeepSeek 未接受此 Key，请在本机重新设置"
                                402 -> "DeepSeek 账户余额不足"
                                429 -> "DeepSeek 请求过于频繁，请稍后重试"
                                400, 413, 422 -> "DeepSeek 未接受此图片，请裁切后重试"
                                else -> "DeepSeek 暂时不可用（HTTP " + it.code + "）"
                            } }
                            val choice = runCatching { JsonParser.parseString(it.body?.string()).asJsonObject
                                .getAsJsonArray("choices").first().asJsonObject }.getOrNull()
                            check(choice?.get("finish_reason")?.asString == "stop") { "未收到完整阅读稿，请裁切图片或稍后重试" }
                            val text = choice?.getAsJsonObject("message")?.get("content")?.takeIf { v -> v.isJsonPrimitive }?.asString.orEmpty().trim()
                            check(text.isNotEmpty()) { "DeepSeek 返回了空阅读稿" }
                            val record = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                            checkNotNull(record) { "DeepSeek 返回的格式不完整，可重试" }
                            fun string(name: String) = record[name]?.takeIf { field -> field.isJsonPrimitive && field.asJsonPrimitive.isString }?.asString?.trim()
                            val markdown = string("markdown").orEmpty()
                            check(markdown.isNotBlank()) { "DeepSeek 未返回阅读稿，可重试" }
                            val topic = string("knowledge_point")?.takeIf { name -> name.isNotBlank() && validTopicText(name) &&
                                name !in setOf("未知", "不确定", "无法判断", "待核对", "〔待核对〕", "null") }
                            result.resume(Transcription(markdown, topic))
                        } catch (e: Exception) {
                            result.resumeWithException(IllegalStateException(if (e.javaClass == IllegalStateException::class.java)
                                e.message ?: "识别失败" else "无法读取 DeepSeek 的识别结果"))
                        }
                    }
                }
            })
        }
    }
}
