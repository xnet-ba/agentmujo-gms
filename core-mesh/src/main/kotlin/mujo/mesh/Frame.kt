package mujo.mesh

import java.nio.ByteBuffer
import java.security.MessageDigest

/** 32B node id. U Fazi 3 = javni ključ; u Fazi 1 izveden iz imena (sha256). */
@JvmInline value class NodeId(val bytes: ByteArray) {
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
    val payload: ByteArray = ByteArray(0),
) {
    companion object {
        const val FLAG_FRAG = 1; const val FLAG_ACK_REQ = 2; const val FLAG_COMPRESSED = 4
        const val HEADER = 1 + 1 + 16 + 32 + 32 + 1 + 1 + 1 + 1 + 8 + 2 // 100
        fun randomId(r: java.util.Random): ByteArray = ByteArray(16).also { r.nextBytes(it) }

        fun encode(f: Frame): ByteArray {
            val frag = if (f.flags and FLAG_FRAG != 0) 2 else 0
            val b = ByteBuffer.allocate(HEADER + frag + f.payload.size)
            b.put(f.version.toByte()); b.put(f.type.toByte())
            b.put(f.messageId); b.put(f.src.bytes); b.put(f.dst.bytes)
            b.put(f.priority.code.toByte()); b.put(f.ttl.toByte()); b.put(f.hopCount.toByte()); b.put(f.flags.toByte())
            b.putLong(f.timestampMs); b.putShort(f.payload.size.toShort())
            if (frag == 2) { b.put(f.fragIndex.toByte()); b.put(f.fragTotal.toByte()) }
            b.put(f.payload)
            return b.array()
        }

        /** null = drop (nepoznat ver/tip/skraćen frame). Nikad ne baca. */
        fun decode(raw: ByteArray, drops: MutableMap<String, Int>? = null): Frame? {
            fun drop(why: String): Frame? { if (drops != null) drops[why] = (drops[why] ?: 0) + 1; return null }
            if (raw.size < HEADER) return drop("short")
            val b = ByteBuffer.wrap(raw)
            val ver = b.get().toInt() and 0xFF
            if (ver != 1) return drop("version")
            val type = b.get().toInt() and 0xFF
            if (type !in 1..6) return drop("type")
            val id = ByteArray(16); b.get(id)
            val s = ByteArray(32); b.get(s); val d = ByteArray(32); b.get(d)
            val prio = b.get().toInt() and 0xFF
            if (prio !in 0..3) return drop("prio")
            val ttl = b.get().toInt() and 0xFF; val hops = b.get().toInt() and 0xFF; val flags = b.get().toInt() and 0xFF
            val ts = b.long; val len = b.short.toInt() and 0xFFFF
            var fi = 0; var ft = 0
            if (flags and FLAG_FRAG != 0) { // Faza 1: FRAG se DROP-a (impl. tek Faza 9)
                return drop("frag-unsupported")
            }
            if (b.remaining() < len) return drop("payload-short")
            val p = ByteArray(len); b.get(p)
            return Frame(ver, type, id, NodeId(s), NodeId(d), Priority.of(prio), ttl, hops, ts, flags, fi, ft, p)
        }
    }
    override fun equals(other: Any?) = other is Frame && version == other.version && type == other.type &&
        messageId.contentEquals(other.messageId) && src.bytes.contentEquals(other.src.bytes)
    override fun hashCode() = messageId.contentHashCode() * 31 + src.bytes.contentHashCode()
}
