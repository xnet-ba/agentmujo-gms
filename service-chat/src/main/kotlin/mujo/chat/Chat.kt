package mujo.chat

import mujo.mesh.MeshNode
import mujo.mesh.NodeId
import mujo.mesh.Priority
import java.nio.ByteBuffer

/**
 * Servisi iznad mreže (Faza 6a, čisti Kotlin — bez Androida, bez direktnog transporta).
 * Grupni chat v1 = broadcast + app filter po grupi (mesh group-dst kasnije); E2E za grupe kasnije.
 */
object ChatFrame {
    const val CHAT: Byte = 0x10
    const val SOS: Byte = 0x20
    const val SOS_ACK: Byte = 0x21

    fun chat(group: String, text: String): ByteArray {
        val g = group.toByteArray().take(255).toByteArray(); val t = text.toByteArray()
        return byteArrayOf(CHAT) + g.size.toByte() + g + t
    }
    fun sos(sosId: ByteArray, lat: Double, lon: Double, text: String): ByteArray {
        val b = ByteBuffer.allocate(1 + 16 + 8 + 8 + text.toByteArray().size)
        b.put(SOS); b.put(sosId); b.putDouble(lat); b.putDouble(lon); b.put(text.toByteArray())
        return b.array()
    }
    fun sosAck(sosId: ByteArray) = byteArrayOf(SOS_ACK) + sosId
}

data class ChatMsg(val from: NodeId, val group: String, val text: String)

class ChatService(val node: MeshNode) {
    private var consumed = 0
    fun sendChat(group: String, text: String) = node.broadcast(ChatFrame.chat(group, text))
    fun poll(): List<ChatMsg> {
        val out = mutableListOf<ChatMsg>()
        while (consumed < node.delivered.size) {
            val f = node.delivered[consumed++]
            val p = f.payload
            if (p.isNotEmpty() && p[0] == ChatFrame.CHAT && p.size >= 2) {
                val gl = p[1].toInt() and 0xFF
                if (p.size >= 2 + gl) out.add(ChatMsg(f.src, String(p.copyOfRange(2, 2 + gl)), String(p.copyOfRange(2 + gl, p.size))))
            }
        }
        return out
    }
}

class SosManager(val node: MeshNode, val quorum: Int = 1) {
    data class Session(val sosId: ByteArray, var sent: Int = 0, var lastSent: Long = 0,
        val ackedBy: MutableSet<String> = mutableSetOf(), var done: Boolean = false,
        var lat: Double = 0.0, var lon: Double = 0.0, var text: String = "")
    private val sessions = mutableMapOf<String, Session>()
    private var consumed = 0
    var onSos: ((from: NodeId, sosId: ByteArray, lat: Double, lon: Double, text: String) -> Unit)? = null

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun startSos(text: String, lat: Double, lon: Double, now: Long): ByteArray {
        val id = ByteArray(16).also { nodeRnd.nextBytes(it) }
        val s = Session(id, lat = lat, lon = lon, text = text)
        sessions[hex(id)] = s; send(s, now)
        return id
    }

    private val nodeRnd = java.util.Random()
    private fun send(s: Session, now: Long) {
        node.broadcast(ChatFrame.sos(s.sosId, s.lat, s.lon, s.text), sos = true)
        s.sent++; s.lastSent = now
    }

    /** Dolazni SOS/SOS_ACK + auto-ACK; rebroadcast svakih 20 tickova, max 10 pokušaja. */
    fun poll(now: Long): List<Session> {
        while (consumed < node.delivered.size) {
            val f = node.delivered[consumed++]
            if (f.src == node.id) continue // vlastiti broadcast: ne onSos sebi, ne self-ack (bug #11)
            val p = f.payload
            if (p.isEmpty()) continue
            when (p[0]) {
                ChatFrame.SOS -> if (p.size >= 33) {
                    val id = p.copyOfRange(1, 17)
                    val b = ByteBuffer.wrap(p, 17, 16)
                    onSos?.invoke(f.src, id, b.double, b.double, String(p.copyOfRange(33, p.size)))
                    node.sendUnicast(f.src, ChatFrame.sosAck(id), Priority.URGENT, ackReq = false, e2e = false)
                }
                ChatFrame.SOS_ACK -> if (p.size == 17) {
                    sessions[hex(p.copyOfRange(1, 17))]?.ackedBy?.add(hex(f.src.bytes))
                }
            }
        }
        sessions.values.filter { !it.done }.forEach { s ->
            if (s.ackedBy.size >= quorum) s.done = true
            else if (now - s.lastSent >= 20) { if (s.sent >= 10) s.done = true else send(s, now) }
        }
        return sessions.values.toList()
    }

    fun session(id: ByteArray) = sessions[hex(id)]
}
