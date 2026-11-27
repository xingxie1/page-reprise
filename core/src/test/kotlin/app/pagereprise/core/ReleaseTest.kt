package app.pagereprise.core

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ReleaseTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val payload = JsonObject().apply {
        addProperty("product", "app.pagereprise")
        addProperty("format", 1)
        addProperty("version", "0.10.0")
        addProperty("androidCode", 10)
        for ((key, extension) in listOf("android" to ".apk", "windows" to "-Windows-x64.exe")) add(key, JsonObject().apply {
            addProperty("name", "PageReprise-0.10.0" + extension)
            addProperty("size", 1024)
            addProperty("sha256", "f".repeat(64))
        })
    }

    private fun signed(body: JsonObject, signedBody: JsonObject = body): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keys.private)
            update(signedBody.toString().toByteArray())
        }.sign()
        return JsonObject().apply {
            addProperty("payload", Base64.getEncoder().encodeToString(body.toString().toByteArray()))
            addProperty("signature", Base64.getEncoder().encodeToString(signature))
        }.toString().toByteArray()
    }

    @Test fun verifiesExactPayloadAndComparesNumericVersions() {
        val release = Release.verify(signed(payload), keys.public)
        assertTrue(release.newerThan("0.9.9"))
        assertFalse(release.newerThan("0.10.0"))
        assertFalse(release.newerThan("1.0.0"))
        assertEquals("PageReprise-0.10.0.apk", release.file(Edition.ANDROID).name)
        assertThrows(IllegalArgumentException::class.java) { release.newerThan("0.10.0-beta") }
    }

    @Test fun rejectsUnsignedEditsWrongProductAndUnexpectedAssetNames() {
        val edited = payload.deepCopy().apply { addProperty("androidCode", 9999) }
        assertThrows(IllegalArgumentException::class.java) { Release.verify(signed(edited, payload), keys.public) }
        val wrongProduct = payload.deepCopy().apply { addProperty("product", "other") }
        assertThrows(IllegalArgumentException::class.java) { Release.verify(signed(wrongProduct), keys.public) }
        val wrongFile = payload.deepCopy().apply { getAsJsonObject("windows").addProperty("name", "../installer.exe") }
        assertThrows(IllegalArgumentException::class.java) { Release.verify(signed(wrongFile), keys.public) }
    }
}
