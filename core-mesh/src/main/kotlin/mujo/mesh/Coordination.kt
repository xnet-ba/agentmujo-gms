package mujo.mesh

import java.nio.ByteBuffer

enum class Role { COORDINATOR, RELAY, MESSENGER, GPS, GATEWAY, STORAGE, VOICE }

/** HELLO payload (link-local, ttl=1, nikad se ne relayuje): batt(1B) flags(1B) epoch(4B) coordSeq(4B) coordId(32B). */
data class HelloInfo(
    val battery: Int, val charging: Boolean, val noRelay: Boolean,
    val epoch: Int, val coordSeq: Int, // seq napreduje SAMO kod koordinatora (liveness); re-advertise ga samo prenosi
    val coordId: NodeId?, // null = ne znam ni za jednog
) {
    companion object {
        val NONE_ID = NodeId(ByteArray(32))
        fun encode(h: HelloInfo): ByteArray {
            val b = ByteBuffer.allocate(42)
            b.put(h.battery.coerceIn(0, 100).toByte())
            b.put((((if (h.charging) 1 else 0)) or (if (h.noRelay) 2 else 0)).toByte())
            b.putInt(h.epoch); b.putInt(h.coordSeq); b.put((h.coordId ?: NONE_ID).bytes)
            return b.array()
        }
        fun decode(p: ByteArray): HelloInfo? {
            if (p.size != 42) return null
            val b = ByteBuffer.wrap(p)
            val batt = b.get().toInt() and 0xFF; val fl = b.get().toInt() and 0xFF
            val ep = b.int; val sq = b.int; val c = ByteArray(32); b.get(c)
            if (batt > 100) return null
            val id = NodeId(c)
            return HelloInfo(batt, fl and 1 != 0, fl and 2 != 0, ep, sq, if (id.bytes.contentEquals(NONE_ID.bytes)) null else id)
        }
    }
}

fun nodeLess(a: NodeId, b: NodeId): Boolean {
    for (i in 0 until 32) { val d = (a.bytes[i].toInt() and 0xFF) - (b.bytes[i].toInt() and 0xFF); if (d != 0) return d < 0 }
    return false
}

/**
 * Lease-based koordinator: mreža radi bez njega; lease sam istekne pa nema trajnih duplikata.
 * Viši epoch pobjeđuje, izjednačeno → manji id. [TESTIRANO-UNIT + SIMULATOR]
 */
class LeaseTracker(val self: NodeId, val leaseTimeout: Long, jitterMod: Int = 6) {
    var coord: NodeId? = null; var epoch = 0; var coordSeq = 0
    private var lastSeen = Long.MIN_VALUE / 2
    private var suspectedAt: Long? = null
    private val jitter: Long = (Math.abs(self.bytes.contentHashCode()) % jitterMod).toLong()

    fun onHello(coordId: NodeId?, ep: Int, seq: Int, at: Long) {
        if (coordId == null) return
        if (coordId == self) { // ja sam izvor: živ sam po definiciji (tickam)
            if (ep >= epoch) { epoch = ep; lastSeen = at; suspectedAt = null }
            return
        }
        // bug #8: re-advertise mrtvog koordinatora držao lease živim zauvijek.
        // lastSeen se osvježava SAMO kad seq napreduje (svježina izvora), ne na ustajalu kopiju.
        if (ep == epoch && coord != null && coordId == coord) {
            if (seq > coordSeq) { coordSeq = seq; lastSeen = at; suspectedAt = null }
            return
        }
        val cur = coord
        if (ep > epoch || (ep == epoch && (cur == null || nodeLess(coordId, cur)))) {
            coord = coordId; epoch = ep; coordSeq = seq; lastSeen = at; suspectedAt = null
        }
        // ep < epoch → stara informacija, ignoriši
    }

    /** true = upravo sam se proglasio (epoch+1, seq=0). */
    fun tick(at: Long): Boolean {
        if (coord != null && coord == self) { lastSeen = at; suspectedAt = null; return false } // izvor ne ističe sam sebi
        if (coord != null && at - lastSeen <= leaseTimeout) return false
        if (suspectedAt == null) suspectedAt = at
        if (at - suspectedAt!! >= jitter) {
            epoch += 1; coord = self; coordSeq = 0; lastSeen = at; suspectedAt = null
            return true
        }
        return false
    }
}
