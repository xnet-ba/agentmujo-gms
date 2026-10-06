package mujo.mesh

import java.nio.ByteBuffer
import java.security.MessageDigest

/** 32B node id. U Fazi 3 = javni ključ; u Fazi 1 izveden iz imena (sha256).
 * Namjerno content-equality (bug #5: value-class delegirao na referentni ByteArray.equals
 * pa isti koordinator viđen od 6 čvorova izgleda kao 6 različitih). */
class NodeId(val bytes: ByteArray) {
    init { require(bytes.size == 32) }
    companion object {
        fun fromName(name: String): NodeId {
            val d = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
            return NodeId(d)
        }
        val BROADCAST = NodeId(ByteArray(32) { 0xFF.toByte() })
        fun group(name: String): NodeId {
            val d = MessageDigest.getInstance("SHA-256").digest(("grp:" + name).toByteArray())
            d[0] = 0x00 // rezervirani prefiks za grupni dst (vidi PROTOCOL.md)
            return NodeId(d)
        }
    }
    fun short(): String = bytes.take(3).joinToString("") { "%02x".format(it) }
    override fun equals(other: Any?) = other is NodeId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "Node(${short()})"
}

object MsgType { const val DATA = 1; const val FLOOD = 2; const val SOS = 3; const val ACK = 4; const val HELLO = 5; const val HINT = 6 }

enum class Priority(val code: Int) { SOS(0), URGENT(1), NORMAL(2), BULK(3);
    companion object { fun of(c: Int) = entries.first { it.code == c } } }

/** Binarni frame po docs/PROTOCOL.md v1 (bez potpisa do Faze 3). */
data class Frame(
    val version: Int = 1,
    val type: Int,
    val messageId: ByteArray, // 16B
    val src: NodeId,
    val dst: NodeId,
    val priority: Priority,
    val ttl: Int,
    val hopCount: Int = 0,
    val timestampMs: Long = 0L, // samo za starost, NE za redoslijed
    val flags: Int = 0,
    val fragIndex: Int = 0, // validno samo ako FRAG bit
    val fragTotal: Int = 0,
    val seq: Int = 0, // Faza 3: monoton po originatoru, replay zaštita (prozor 8)
    val edPub: ByteArray = ByteArray(32), // Faza 3: originatorov Ed25519 ključ (nodeId MORA = sha256(edPub))
    val xPub: ByteArray = ByteArray(32),  // Faza 3: originatorov X25519 ključ (E2E direktorij iz overheard frameova)
    val payload: ByteArray = ByteArray(0),
    val sig: ByteArray = ByteArray(0), // Faza 3: Ed25519 potpis nad signBytes, OBAVEZAN (prazan → drop)
) {
    companion object {
        const val FLAG_FRAG = 1; const val FLAG_ACK_REQ = 2; const val FLAG_COMPRESSED = 4
        const val FLAG_SEALED = 8 // Faza 3: payload je E2E box za dst
        const val HEADER = 1 + 1 + 16 + 32 + 32 + 32 + 32 + 1 + 1 + 1 + 1 + 8 + 4 + 2 // 164
        fun randomId(r: java.util.Random): ByteArray = ByteArray(16).also { r.nextBytes(it) }

        /** Kanonski bajtovi za potpis: sve OSIM ttl/hops/sig (relay smije dirati samo ta dva). */
        fun signBytes(f: Frame): ByteArray = encode(f.copy(ttl = 0, hopCount = 0, sig = ByteArray(0)))

        fun encode(f: Frame): ByteArray {
            val frag = if (f.flags and FLAG_FRAG != 0) 2 else 0
            val b = ByteBuffer.allocate(HEADER + frag + f.payload.size + 2 + f.sig.size)
            b.put(f.version.toByte()); b.put(f.type.toByte())
            b.put(f.messageId); b.put(f.src.bytes); b.put(f.dst.bytes)
            b.put(f.edPub); b.put(f.xPub)
            b.put(f.priority.code.toByte()); b.put(f.ttl.toByte()); b.put(f.hopCount.toByte()); b.put(f.flags.toByte())
            b.putLong(f.timestampMs); b.putInt(f.seq); b.putShort(f.payload.size.toShort())
            if (frag == 2) { b.put(f.fragIndex.toByte()); b.put(f.fragTotal.toByte()) }
            b.put(f.payload)
            b.putShort(f.sig.size.toShort()); b.put(f.sig)
            return b.array()
        }

        /** null = drop (nepoznat ver/tip/skraćen frame/prazan potpis). Nikad ne baca. */
        fun decode(raw: ByteArray, drops: MutableMap<String, Int>? = null): Frame? {
            fun drop(why: String): Frame? { if (drops != null) drops[why] = (drops[why] ?: 0) + 1; return null }
            if (raw.size < HEADER + 2) return drop("short")
            val b = ByteBuffer.wrap(raw)
            val ver = b.get().toInt() and 0xFF
            if (ver != 1) return drop("version")
            val type = b.get().toInt() and 0xFF
            if (type !in 1..6) return drop("type")
            val id = ByteArray(16); b.get(id)
            val s = ByteArray(32); b.get(s); val d = ByteArray(32); b.get(d)
            val ep = ByteArray(32); b.get(ep); val xp = ByteArray(32); b.get(xp)
            val prio = b.get().toInt() and 0xFF
            if (prio !in 0..3) return drop("prio")
            val ttl = b.get().toInt() and 0xFF; val hops = b.get().toInt() and 0xFF; val flags = b.get().toInt() and 0xFF
            val ts = b.long; val sq = b.int; val len = b.short.toInt() and 0xFFFF
            if (flags and FLAG_FRAG != 0) { // FRAG impl. tek Faza 9
                return drop("frag-unsupported")
            }
            if (b.remaining() < len + 2) return drop("payload-short")
            val p = ByteArray(len); b.get(p)
            val sigLen = b.short.toInt() and 0xFFFF
            if (sigLen == 0) return drop("no-sig") // Faza 3: nepotpisan frame se ne prima
            if (b.remaining() != sigLen) return drop("sig-size")
            val sg = ByteArray(sigLen); b.get(sg)
            return Frame(ver, type, id, NodeId(s), NodeId(d), Priority.of(prio), ttl, hops, ts, flags, 0, 0, sq, ep, xp, p, sg)
        }
    }
    override fun equals(other: Any?) = other is Frame && version == other.version && type == other.type &&
        messageId.contentEquals(other.messageId) && src.bytes.contentEquals(other.src.bytes)
    override fun hashCode() = messageId.contentHashCode() * 31 + src.bytes.contentHashCode()
}
