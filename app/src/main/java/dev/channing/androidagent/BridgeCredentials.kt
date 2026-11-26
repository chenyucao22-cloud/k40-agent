package dev.channing.androidagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only a dedicated bridge credential, never an OpenAI/ChatGPT login token. */
class BridgeCredentials(private val context: Context) {
    data class Config(val origin: String, val token: String, val siteToken: String = "")
    private val prefs get() = context.getSharedPreferences("bridge_config", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("bridge-aes-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("bridge-aes-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(origin: String, token: String, siteToken: String = "") {
        val endpoint = EndpointPolicy.origin(origin)
        require(Regex("[A-Za-z0-9_-]{43,128}").matches(token))
        require(siteToken.isEmpty() || Regex("[A-Za-z0-9_-]{43,128}").matches(siteToken))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(endpoint.toByteArray())
        val encrypted = cipher.doFinal((token + "\n" + siteToken).toByteArray())
        check(prefs.edit().putString("origin", endpoint)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun load(): Config? {
        val origin = prefs.getString("origin", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        }
        cipher.updateAAD(EndpointPolicy.origin(origin).toByteArray())
        val token = String(cipher.doFinal(Base64.decode(prefs.getString("token", ""), Base64.NO_WRAP)), Charsets.UTF_8)
        val parts = token.split("\n", limit = 2)
        return Config(origin, parts[0], parts.getOrElse(1) { "" })
    }
    fun origin(): String = prefs.getString("origin", "").orEmpty()
    fun clear() { prefs.edit().clear().commit() }
}
