package se.takcampus.voice.watch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

data class VoiceSettings(
    val host: String = "", val port: Int = 64738, val username: String = "",
    val password: String = "", val identity: ByteArray? = null, val identityPassword: String = "", val ca: ByteArray? = null,
    val displayName: String = "", val setupChannels: String = "", val defaultChannelId: Int = -1,
    val defaultChannelName: String = "", val credentialExpiresAt: Long = 0,
) {
    fun tls(): SSLContext {
        val keyManagers = identity?.let { bytes ->
            val store = KeyStore.getInstance("PKCS12"); val pass = identityPassword.toCharArray()
            try {
                store.load(bytes.inputStream(), pass)
                require(store.aliases().toList().any { store.isKeyEntry(it) }) { "No private key in identity" }
                store.aliases().toList().filter { store.isKeyEntry(it) }.forEach { (store.getCertificate(it) as X509Certificate).checkValidity() }
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, pass) }.keyManagers
            } finally { pass.fill('\u0000') }
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (ca == null) trust.init(null as KeyStore?)
        else {
            val certificates = CertificateFactory.getInstance("X.509").generateCertificates(ca.inputStream())
            require(certificates.isNotEmpty() && certificates.size <= 8)
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            certificates.forEachIndexed { index, cert ->
                val x509 = cert as X509Certificate; require(x509.basicConstraints >= 0)
                x509.checkValidity(); store.setCertificateEntry("voice-ca-$index", cert)
            }
            trust.init(store)
        }
        return SSLContext.getInstance("TLS").apply { init(keyManagers, trust.trustManagers, null) }
    }
}

/** Credentials and imported private-key material are sealed together; no plaintext fallback. */
object SettingsStore {
    private const val ALIAS = "voice-settings-aes-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "voice-settings.vault"))
    @Synchronized fun load(context: Context): VoiceSettings {
        val bytes = try { file(context).readFully() } catch (_: java.io.FileNotFoundException) { return VoiceSettings() }
        require(bytes.size in 30..524288 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val clear = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        try {
            val obj = JSONObject(String(clear, Charsets.UTF_8))
            fun blob(name: String): ByteArray? = obj.optString(name).takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) }
            return VoiceSettings(obj.getString("host"), obj.getInt("port"), obj.getString("username"), obj.optString("password"),
                blob("identity"), obj.optString("identityPassword"), blob("ca"),
                obj.optString("displayName"), obj.optString("setupChannels"), obj.optInt("defaultChannelId", -1),
                obj.optString("defaultChannelName"), obj.optLong("credentialExpiresAt", 0))
        } finally { clear.fill(0) }
    }
    @Synchronized fun save(context: Context, settings: VoiceSettings) {
        require(settings.port in 1..65535 && settings.host.length <= 253 && settings.username.length <= 128 && settings.password.length <= 1024)
        val obj = JSONObject().put("host", settings.host).put("port", settings.port).put("username", settings.username)
            .put("password", settings.password).put("identityPassword", settings.identityPassword)
            .put("identity", settings.identity?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
            .put("ca", settings.ca?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
        obj.put("displayName", settings.displayName).put("setupChannels", settings.setupChannels)
            .put("defaultChannelId", settings.defaultChannelId).put("defaultChannelName", settings.defaultChannelName)
            .put("credentialExpiresAt", settings.credentialExpiresAt)
        val clear = obj.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(clear); clear.fill(0)
        val target = file(context); val stream = target.startWrite()
        try { stream.write(encrypted); target.finishWrite(stream) }
        catch (error: Exception) { target.failWrite(stream); throw error }
    }
}
