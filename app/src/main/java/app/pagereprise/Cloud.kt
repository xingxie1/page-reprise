package app.pagereprise

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.pagereprise.core.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Session(val site: String, val key: String, val email: String, val uid: String,
    val access: String, val refresh: String, val expiry: Long) {
    val owner get() = digest(site.toByteArray()) + ":" + uid
}

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "page-reprise-session-v1"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): Session? {
        val box = prefs.getString("session", null)?.split(".") ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(box.first(), Base64.NO_WRAP)))
        return Codec.json.fromJson(String(cipher.doFinal(Base64.decode(box.last(), Base64.NO_WRAP)), Charsets.UTF_8), Session::class.java)
    }
    fun write(session: Session?) {
        if (session == null) { check(prefs.edit().remove("session").commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val box = listOf(cipher.iv, cipher.doFinal(Codec.json.toJson(session).toByteArray()))
            .joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
        check(prefs.edit().putString("session", box).commit())
    }
}

class Cloud {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    private fun fields(vararg pairs: Pair<String, String>) = JsonObject().apply { pairs.forEach { addProperty(it.first, it.second) } }
    private fun json(response: Response): String = response.use {
        if (!it.isSuccessful) throw IOException("云端请求失败（HTTP " + it.code + "）；请检查网络、账号、邀请及服务配置")
        it.body?.string().orEmpty()
    }
    private fun request(site: String, key: String, path: String, access: String? = null) =
        Request.Builder().url(site + path).header("apikey", key).apply { if (access != null) header("Authorization", "Bearer " + access) }
    private fun token(site: String, key: String, path: String, args: JsonObject): Session {
        val req = request(site, key, path).post(args.toString().toRequestBody(jsonType)).build()
        val result = JsonParser.parseString(json(http.newCall(req).execute())).asJsonObject
        val user = result.getAsJsonObject("user")
        val uid = java.util.UUID.fromString(user.get("id").asString).toString()
        return Session(site, key, user.get("email").asString, uid, result.get("access_token").asString,
            result.get("refresh_token").asString, System.currentTimeMillis() / 1000 + result.get("expires_in").asLong)
    }

    fun login(url: String, publicKey: String, email: String, password: String, inviteCode: String): Session {
        val parsed = url.trim().trimEnd('/').toHttpUrl()
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.encodedPath == "/"
            && parsed.query == null && parsed.fragment == null) { "服务地址必须是 HTTPS 根地址" }
        val key = publicKey.trim()
        require(key.isNotBlank() && !key.startsWith("sb_secret_")) { "请使用项目公开密钥，不要输入服务端密钥" }
        if (key.contains(".")) {
            val role = runCatching { JsonParser.parseString(String(Base64.decode(key.split(".")[1], Base64.URL_SAFE or Base64.NO_WRAP))).asJsonObject.get("role").asString }.getOrNull()
            require(role == "anon") { "只接受 anon 或 publishable key" }
        } else require(key.startsWith("sb_publishable_")) { "公开密钥格式不正确" }
        val site = parsed.toString().trimEnd('/')
        val auth = if (inviteCode.isBlank()) token(site, key, "/auth/v1/token?grant_type=password",
            fields("email" to email.trim(), "password" to password)) else {
            require(password.length >= 8) { "请设置至少 8 位密码" }
            val invited = token(site, key, "/auth/v1/verify", fields("type" to "invite", "email" to email.trim(), "token" to inviteCode.trim()))
            val req = request(site,key,"/auth/v1/user",invited.access).put(fields("password" to password).toString().toRequestBody(jsonType)).build()
            json(http.newCall(req).execute())
            invited
        }
        val check = request(site, key, "/rest/v1/rpc/reprise_member", auth.access).post("{}".toRequestBody(jsonType)).build()
        require(json(http.newCall(check).execute()).trim() == "true") { "该账号尚未获邀" }
        return auth
    }

    fun fresh(auth: Session): Session {
        if (auth.expiry > System.currentTimeMillis() / 1000 + 90) return auth
        return token(auth.site, auth.key, "/auth/v1/token?grant_type=refresh_token", fields("refresh_token" to auth.refresh))
            .also { check(it.uid == auth.uid) }
    }

    private fun imagePath(auth: Session, hash: String, download: Boolean) = "/storage/v1/object/" +
        (if (download) "authenticated/" else "") + "reprise-images/" + auth.uid + "/" + hash

    suspend fun sync(auth: Session, store: Store, media: Media, onMetadata: suspend () -> Unit) {
        val pending = store.dao.entries(auth.owner).filter { it.pending }
        val uploaded = mutableSetOf<String>()
        for (batch in pending.chunked(80)) {
            val events = batch.map { Codec.decode(it.payload) }
            for (hash in events.images()) {
                if (!uploaded.add(hash)) continue
                val source = media.file(hash)
                require(source.isFile) { "本地题图缺失，请从备份恢复" }
                val req = request(auth.site, auth.key, imagePath(auth, hash, false), auth.access)
                    .header("x-upsert", "false").post(source.asRequestBody("image/jpeg".toMediaType())).build()
                http.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        val error = response.body?.string().orEmpty()
                        if (response.code !in listOf(400,409) || !(error.contains("Duplicate",true) ||
                            error.contains("already exists",true) || error.contains("409"))) throw IOException("题图未上传（HTTP " + response.code + "）")
                    }
                }
            }
            val records = JsonArray().apply { events.forEach { event ->
                add(fields("owner" to auth.uid, "event_id" to event.id, "card_id" to event.card).apply {
                    add("payload", Codec.json.toJsonTree(event))
                })
            } }
            val req = request(auth.site, auth.key, "/rest/v1/study_events?on_conflict=owner,event_id", auth.access)
                .header("Prefer","resolution=ignore-duplicates,return=minimal").post(records.toString().toRequestBody(jsonType)).build()
            json(http.newCall(req).execute())
            store.dao.sent(auth.owner, batch.map { it.eventId })
        }
        var cursor = ""
        while (true) {
            val route = "/rest/v1/study_events?select=event_id,card_id,payload&owner=eq." + auth.uid +
                "&order=event_id.asc&limit=300" + if (cursor.isEmpty()) "" else "&event_id=gt." + cursor
            val req = request(auth.site, auth.key, route, auth.access).get().build()
            val rows = JsonParser.parseString(json(http.newCall(req).execute())).asJsonArray
            if (rows.isEmpty) break
            val events = rows.map { element ->
                val row = element.asJsonObject
                Codec.decode(row.get("payload").toString()).also { require(it.id == row.get("event_id").asString && it.card == row.get("card_id").asString) }
            }
            store.merge(auth.owner, events, false)
            cursor = events.last().id
        }
        onMetadata()
        val hashes = store.dao.entries(auth.owner).map { Codec.decode(it.payload) }.images()
        for (hash in hashes.filterNot { media.file(it).isFile }) {
            val req = request(auth.site, auth.key, imagePath(auth, hash, true), auth.access).get().build()
            http.newCall(req).execute().use {
                if (!it.isSuccessful) throw IOException("题图待下载（HTTP " + it.code + "）")
                media.accept(hash, requireNotNull(it.body).byteStream())
            }
        }
    }
}
