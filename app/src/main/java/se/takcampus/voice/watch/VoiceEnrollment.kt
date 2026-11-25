package se.takcampus.voice.watch

import android.os.Binder
import com.weartak.voice.api.IVoiceEnrollment
import com.weartak.voice.api.IVoiceRequestSigner
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class VoiceEnrollment(private val host: IVoiceEnrollment, private val hostUid: Int) {
    companion object { @Volatile var current: VoiceEnrollment? = null }
    fun profiles(): List<Pair<String, String>> {
        val profiles = host.profiles()
        return profiles.getStringArray("ids").orEmpty().zip(profiles.getStringArray("labels").orEmpty())
    }

    fun issue(settings: VoiceSettings, profile: String, installation: String): VoiceSettings {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val available = AtomicBoolean(true)
        val signer = object : IVoiceRequestSigner.Stub() {
            override fun sign(request: ByteArray): ByteArray {
                check(Binder.getCallingUid() == hostUid && current === this@VoiceEnrollment &&
                    request.size in 1..16384 && available.compareAndSet(true, false))
                return Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(request); sign() }
            }
        }
        val result = try { host.enroll(profile, installation, pair.public.encoded, signer) }
            finally { available.set(false) }
        result.getString("error")?.let { throw IllegalStateException(it) }
        val count = result.getInt("count")
        require(count in 1..16)
        val parser = CertificateFactory.getInstance("X.509")
        val chain = Array(count) { i ->
            parser.generateCertificate(requireNotNull(result.getByteArray("cert$i")).inputStream()) as X509Certificate
        }
        chain.first().checkValidity()
        require(chain.first().publicKey.encoded.contentEquals(pair.public.encoded))
        val password = UUID.randomUUID().toString()
        val chars = password.toCharArray()
        val bytes = try {
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, chars)
            store.setKeyEntry("voice", pair.private, chars, chain)
            ByteArrayOutputStream().use { store.store(it, chars); it.toByteArray() }
        } finally { chars.fill('\u0000') }
        check(current === this)
        return settings.copy(username = "", password = "", credentialExpiresAt = 0, identity = bytes,
            identityPassword = password, enrollmentProfile = profile).also { it.tls() }
    }
}
