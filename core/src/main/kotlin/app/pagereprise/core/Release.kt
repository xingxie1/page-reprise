package app.pagereprise.core

import com.google.gson.JsonParser
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class Edition { ANDROID, WINDOWS }

data class ReleaseFile(val name: String, val size: Long, val sha256: String) {
    fun matches(file: File): Boolean {
        if (!file.isFile || file.length() != size) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().hex() == sha256
    }
}

data class Release(val version: String, val androidCode: Int, val android: ReleaseFile, val windows: ReleaseFile) {
    fun file(edition: Edition) = if (edition == Edition.ANDROID) android else windows
    fun newerThan(installed: String) = compareVersions(version, installed) > 0

    companion object {
        private val versionPattern = Regex("(0|[1-9][0-9]{0,7})\\.(0|[1-9][0-9]{0,7})\\.(0|[1-9][0-9]{0,7})")
        private const val SIGNING_KEY = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAovA61nS2zAcANVlIVZXO95JhbZyVGawjS6pQTR8TTQc5uskC9FLT4EPzGKwCr+jKZWaDGYtbgKGDP95eK/QhSKueyrghNSKWFhWzkNxmUd6rqJGh+WeUMluq8FrJ40FPXxaVmjZZ3ctaMOzMzcZTjhFx6dHrayBtrnzI4lowdP92JQ99AeDifF99M/JXCAOV9Rqx/FN9ppgCzXzCuQaCm3QoRNbFh2yR3EhzHAFaasHjllaAKmYB31MBX/2zPr1IzcDqhpWR6pfarAixIaz7qkK2Sr15Gfe2078Y5zLkwzM/y20MUSevEhz5lMqpITLiq3hptPstVV+GVYAKaMaawpJF0wyIhHDtZM6HVwnRAp+CWtqVDPGsWJc+xLtXY3S7O3GNWPfHhEaWV4SrhuwGIe9BBosVtioc8LEFOLw31SlxFsNWdQw/uZCPiRK3wqBLnzq8OAgfI2/tMsdFQzmcU5NZpZwr7rcgI7gVmzyWgL1ZXXMHZyWLakHlazY7+bulAgMBAAE="
        private val signingKey by lazy {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(SIGNING_KEY)))
        }

        fun compareVersions(left: String, right: String): Int {
            require(versionPattern.matches(left) && versionPattern.matches(right))
            val a = left.split('.').map(String::toInt)
            val b = right.split('.').map(String::toInt)
            return a.indices.firstOrNull { a[it] != b[it] }?.let { a[it].compareTo(b[it]) } ?: 0
        }

        fun verify(envelope: ByteArray): Release = verify(envelope, signingKey)

        internal fun verify(envelope: ByteArray, key: PublicKey): Release {
            require(envelope.size <= 32 * 1024)
            val wrapper = JsonParser.parseString(envelope.toString(Charsets.UTF_8)).asJsonObject
            val payload = Base64.getDecoder().decode(wrapper.get("payload").asString)
            val signature = Base64.getDecoder().decode(wrapper.get("signature").asString)
            val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(key); update(payload) }
            require(verifier.verify(signature)) { "更新清单签名不匹配" }
            val body = JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
            require(body.get("product").asString == "app.pagereprise" && body.get("format").asInt == 1)
            val version = body.get("version").asString
            val code = body.get("androidCode").asInt
            require(versionPattern.matches(version) && code > 0)
            fun artifact(key: String, suffix: String, limit: Long): ReleaseFile {
                val obj = body.getAsJsonObject(key)
                val f = ReleaseFile(obj.get("name").asString, obj.get("size").asLong, obj.get("sha256").asString)
                require(f.name == "PageReprise-" + version + suffix && f.size in 1..limit && f.sha256.matches(Regex("[a-f0-9]{64}")))
                return f
            }
            return Release(version, code, artifact("android", ".apk", 128L shl 20), artifact("windows", "-Windows-x64.exe", 512L shl 20))
        }
    }
}
