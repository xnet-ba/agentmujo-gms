package mujo.mesh

import java.security.*
import java.security.spec.NamedParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Kriptografija Faze 3: isključivo provjereni JDK provideri (SunEC/SunJCE), bez JNI/native.
 * Ne implementiramo primitve — komponujemo: Ed25519 potpis, X25519+HKDF+ChaCha20-Poly1305 box.
 * [TESTIRANO-UNIT] (roundtrip, tamper, wrong-key). Android API nivoi: TODO, vidi DECISIONS.md t.8.
 */
class Identity(
    val edPriv: PrivateKey, val edPub: PublicKey,
    val xPriv: PrivateKey, val xPub: PublicKey,
) {
    val edPubBytes: ByteArray get() = edPub.encoded.takeLast(32).toByteArray() // SubjectPublicKeyInfo rep → sirovi ključ
    val xPubBytes: ByteArray get() = xPub.encoded.takeLast(32).toByteArray()

    fun nodeId(): NodeId = NodeId(MessageDigest.getInstance("SHA-256").digest(edPubBytes))

    fun sign(data: ByteArray): ByteArray {
        val s = Signature.getInstance("Ed25519"); s.initSign(edPriv); s.update(data)
        return s.sign()
    }

    companion object {
        fun verify(pub: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
            val s = Signature.getInstance("Ed25519"); s.initVerify(pub); s.update(data); s.verify(sig)
        } catch (_: Exception) { false }

        private val TPL = ByteArray(32) { 7 } // sjeme za rekonstrukciju X.509 prefiksa (samo oblik, ne tajna)

        fun parseEdPub(raw32: ByteArray): PublicKey? = try {
            // rekonstrukcija X.509 ovojnice oko sirovog ključa (isti provider koji ga je izdao)
            val tmp = deterministic(TPL).edPub.encoded
            val prefix = tmp.copyOfRange(0, tmp.size - 32)
            KeyFactory.getInstance("Ed25519").generatePublic(
                java.security.spec.X509EncodedKeySpec(prefix + raw32))
        } catch (_: Exception) { null }

        fun parseXPub(raw32: ByteArray): PublicKey? = try {
            val tmp = deterministic(TPL).xPub.encoded
            val prefix = tmp.copyOfRange(0, tmp.size - 32)
            KeyFactory.getInstance("X25519").generatePublic(
                java.security.spec.X509EncodedKeySpec(prefix + raw32))
        } catch (_: Exception) { null }

        /** Produkcioni identitet (SecureRandom). Deterministički samo za sim/testove. */
        fun random(): Identity {
            val eg = KeyPairGenerator.getInstance("Ed25519").also { it.initialize(NamedParameterSpec.ED25519) }
            val e = eg.generateKeyPair()
            val xg = KeyPairGenerator.getInstance("X25519").also { it.initialize(NamedParameterSpec.X25519) }
            val x = xg.generateKeyPair()
            return Identity(e.private, e.public, x.private, x.public)
        }

        /** Deterministički identitet iz 32B sjemena (za simulator; produkcija koristi SecureRandom). */
        fun deterministic(seed32: ByteArray): Identity {
            require(seed32.size == 32)
            val rnd = SecureRandom.getInstance("SHA1PRNG").also { it.setSeed(seed32) }
            val eg = KeyPairGenerator.getInstance("Ed25519").also { it.initialize(NamedParameterSpec.ED25519, rnd) }
            val e = eg.generateKeyPair()
            val xg = KeyPairGenerator.getInstance("X25519").also { it.initialize(NamedParameterSpec.X25519, rnd) }
            val x = xg.generateKeyPair() // JEDAN par: private+public moraju pripadati zajedno (bug #9)
            return Identity(e.private, e.public, x.private, x.public)
        }

        // ---- E2E box: ephPub(32) | nonce(12) | ct+tag ----
        private const val INFO = "mujo-e2e-v1"

        fun hkdf(secret: ByteArray, info: String, outLen: Int = 32): ByteArray {
            val mac = { k: ByteArray -> Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(k, "HmacSHA256")) } }
            val prk = mac(ByteArray(32)).doFinal(secret) // extract, salt = zeros (RFC 5869: salt opcionalan)
            var t = ByteArray(0); var out = ByteArray(0); var c = 1
            while (out.size < outLen) {
                val m = mac(prk); m.update(t); m.update(info.toByteArray()); m.update(c.toByte())
                t = m.doFinal(); out += t; c++
            }
            return out.copyOf(outLen)
        }

        fun seal(plain: ByteArray, recipientXPub: PublicKey, aad: ByteArray, rnd: java.util.Random): ByteArray {
            val eph = KeyPairGenerator.getInstance("X25519").generateKeyPair()
            val ka = KeyAgreement.getInstance("X25519").also { it.init(eph.private); it.doPhase(recipientXPub, true) }
            val key = hkdf(ka.generateSecret(), INFO)
            val nonce = ByteArray(12).also { rnd.nextBytes(it) }
            val c = Cipher.getInstance("ChaCha20-Poly1305").also {
                it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
                it.updateAAD(aad)
            }
            val ct = c.doFinal(plain)
            return eph.public.encoded.takeLast(32).toByteArray() + nonce + ct
        }

        fun open(box: ByteArray, ownXPriv: PrivateKey, aad: ByteArray): ByteArray? {
            try {
                if (box.size < 32 + 12 + 16) return null
                val ephRaw = box.copyOfRange(0, 32)
                val ephPub = parseXPub(ephRaw) ?: return null
                val ka = KeyAgreement.getInstance("X25519").also { it.init(ownXPriv); it.doPhase(ephPub, true) }
                val key = hkdf(ka.generateSecret(), INFO)
                val c = Cipher.getInstance("ChaCha20-Poly1305").also {
                    it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"),
                        IvParameterSpec(box.copyOfRange(32, 44)))
                    it.updateAAD(aad)
                }
                return c.doFinal(box.copyOfRange(44, box.size))
            } catch (_: Exception) { return null }
        }
    }
}
