package mujo.mesh

/** Odlazni paket: `to==null` = svim trenutnim susjedima (flood /HELLO). */
data class Tx(val bytes: ByteArray, val to: NodeId?)

private data class Queued(var frame: Frame, var to: NodeId?, var attempts: Int = 0, var notBefore: Long = 0)

/**
 * Mesh čvor — čisti Kotlin, bez Android importa. [TESTIRANO-SIMULATOR] preko sim/ scenarija.
 * Tick jedinica = apstraktni korak (sim ga mapira na ms).
 */
class MeshNode(
    val id: NodeId,
    val isGroupMember: (NodeId) -> Boolean = { false },
    private val rng: java.util.Random = java.util.Random(),
    private val storeQuota: Int = 64,
    private val maxAttempts: Int = 4,
    private val ackTimeout: Long = 5,
    private val txBudgetPerTick: Int = 8,
) {
    val neighbors = mutableSetOf<NodeId>()
    val delivered = mutableListOf<Frame>()          // predano aplikaciji, max 1x po (src,msgId)
    val drops = mutableMapOf<String, Int>()         // dijagnostika
    val routes = mutableMapOf<String, NodeId>()     // dstHex -> nextHop (path learning)
    val acked = mutableSetOf<String>()              // potvrđene vlastite poruke
    val lost = mutableListOf<String>()              // odustale nakon maxAttempts
    var forwarded = 0; var sent = 0                 // brojači za testove

    private val seen = LinkedHashSet<String>()      // dedup ključevi (srcHex+idHex)
    private val queues = Array(4) { ArrayDeque<Queued>() } // indeks = prio.code
    private val pending = mutableMapOf<String, Queued>()   // ack-retry: ključ vlastite poruke
    private val store = ArrayDeque<Frame>()         // store-and-forward kad nema susjeda
    private val sosTimes = mutableMapOf<String, MutableList<Long>>() // rate limit po originatoru
    private var sentSinceBulk = 0
    private var now: Long = 0
    private var lastNbrs = HashSet<NodeId>() // TODO: NodeId treba content-equals; ovdje su instance kanonske (sim), na Androidu popraviti

    private fun key(src: NodeId, id: ByteArray) = src.bytes.contentHashCode().toString(36) + ":" + id.contentHashCode().toString(36)
    private fun hex(n: NodeId) = n.bytes.joinToString("") { "%02x".format(it) }
    private fun markSeen(k: String): Boolean { // true = prvi put
        if (!seen.add(k)) return false
        if (seen.size > 4096) { val ite = seen.iterator(); repeat(512) { ite.next(); ite.remove() } } // ponytail: fiksni cap, LRU tek ako mjerenje pokaže potrebu
        return true
    }

    // ---- API za aplikaciju ----
    fun sendUnicast(dst: NodeId, payload: ByteArray, prio: Priority = Priority.NORMAL, ackReq: Boolean = true) {
        val f = Frame(type = MsgType.DATA, messageId = Frame.randomId(rng), src = id, dst = dst,
            priority = prio, ttl = 16, timestampMs = now,
            flags = if (ackReq) Frame.FLAG_ACK_REQ else 0, payload = payload)
        enqueue(f, routes[hex(dst)])
    }

    fun broadcast(payload: ByteArray, sos: Boolean = false) {
        val f = Frame(type = if (sos) MsgType.SOS else MsgType.FLOOD, messageId = Frame.randomId(rng),
            src = id, dst = NodeId.BROADCAST, priority = if (sos) Priority.SOS else Priority.NORMAL,
            ttl = 16, timestampMs = now, payload = payload)
        markSeen(key(f.src, f.messageId)); delivered.add(f) // vlastiti broadcast se i meni isporučuje 1x
        enqueue(f, null)
    }

    private fun enqueue(f: Frame, to: NodeId?) {
        if (neighbors.isEmpty() && to != null) { stash(f); return } // izoliran: store-and-forward
        queues[f.priority.code].addLast(Queued(f, to))
    }

    private fun stash(f: Frame) {
        if (store.size >= storeQuota) { // izbacivanje: najniži prioritet, pa najstariji
            val worst = store.maxWithOrNull(compareBy({ it.priority.code }, { it.timestampMs }))
            if (worst != null && worst.priority.code > f.priority.code) store.remove(worst) else {
                drops["store-full"] = (drops["store-full"] ?: 0) + 1; return
            }
        }
        store.addLast(f)
    }

    // ---- dolazni frame sa linka ----
    fun receive(raw: ByteArray, from: NodeId, at: Long): List<Tx> {
        now = at
        val out = mutableListOf<Tx>()
        val f = Frame.decode(raw, drops) ?: return out
        val k = key(f.src, f.messageId)
        routes[hex(f.src)] = from // path learning: tko me čuo, kuda mu se vraća
        if (f.type == MsgType.ACK) { // payload ACK-a = origSrc(32B) + origId(16B)
            if (f.payload.size != 48) { drops["ack-malformed"] = (drops["ack-malformed"] ?: 0) + 1; return out }
            if (!markSeen(k)) return out // dupli ACK: ništa (idempotentnost)
            val os = NodeId(f.payload.copyOfRange(0, 32)); val oid = f.payload.copyOfRange(32, 48)
            if (os.bytes.contentEquals(id.bytes)) { // ja sam originator → potvrdi
                pending.remove(key(os, oid))?.let { acked.add(key(os, oid)) }
                return out
            }
            // tuđi ACK: proslijedi naučenim putem, ne guti (bug #1: relay je gutao ACK)
            if (f.ttl <= 1) { drops["ttl"] = (drops["ttl"] ?: 0) + 1; return out }
            val rf = f.copy(ttl = f.ttl - 1, hopCount = f.hopCount + 1)
            queues[rf.priority.code].addLast(Queued(rf, routes[hex(os)])); forwarded++
            return out
        }
        val mine = f.dst.bytes.contentEquals(id.bytes) || f.dst.bytes.contentEquals(NodeId.BROADCAST.bytes) || isGroupMember(f.dst)
        if (!markSeen(k)) return out // duplikat: ni isporuka ni relay (idempotentnost)
        if (mine) {
            delivered.add(f)
            if (f.flags and Frame.FLAG_ACK_REQ != 0) {
                val ackPay = f.src.bytes + f.messageId
                val ack = Frame(type = MsgType.ACK, messageId = Frame.randomId(rng), src = id,
                    dst = f.src, priority = Priority.URGENT, ttl = 16, payload = ackPay)
                out.add(Tx(Frame.encode(ack), routes[hex(f.src)])) // unicast natrag naučenim putem
            }
            if (f.dst.bytes.contentEquals(id.bytes)) return out // unicast meni: ne relayaj
            // broadcast/grupni meni: padam kroz na relay (kontrolisani flooding)
        }
        if (f.ttl <= 1) { drops["ttl"] = (drops["ttl"] ?: 0) + 1; return out }
        if (f.type == MsgType.SOS) { // rate limit prioriteta po originatoru: 4/60 tickova
            val t = (sosTimes.getOrPut(hex(f.src)) { mutableListOf() })
            t.removeIf { at - it > 60 }; t.add(at)
            if (t.size > 4) { drops["sos-ratelimit"] = (drops["sos-ratelimit"] ?: 0) + 1; return out }
        }
        val next = if (mine || f.type == MsgType.SOS || f.type == MsgType.FLOOD) null
                   else routes[hex(f.dst)] // unicast poznatom rutom, inače ograničeni flood
        // unicast bez rute: flood ali kratkog dometa da ne poplavi mrežu
        val newTtl = if (!mine && f.type == MsgType.DATA && routes[hex(f.dst)] == null) minOf(f.ttl - 1, 4) else f.ttl - 1
        val rf = f.copy(ttl = newTtl, hopCount = f.hopCount + 1)
        if (!mine && f.type == MsgType.DATA && next == null) {
            // bug #4: relay bez rute je samo flood-ovao pa poruka ispari u particiji
            // (svi su je "vidjeli", post-merge retry umire kao duplikat).
            // Zato relay STASHA kopiju i šalje je ponovo tek kad se skup susjeda promijeni.
            stash(rf)
        }
        queues[rf.priority.code].addLast(Queued(rf, next)); forwarded++
        return out
    }

    // ---- tick: retry isteklih + slanje po prioritetu ----
    fun tick(at: Long): List<Tx> {
        now = at
        val out = mutableListOf<Tx>()
        // store-and-forward: flush SAMO kad se skup susjeda promijenio (merge/oporavak),
        // inače bi se store vrtio u krug svakog ticka
        val cur = HashSet(neighbors)
        if (store.isNotEmpty() && cur != lastNbrs && cur.isNotEmpty()) {
            val sorted = store.sortedWith(compareBy({ it.priority.code }, { it.timestampMs }))
            store.clear(); sorted.forEach { enqueue(it, routes[hex(it.dst)]) }
        }
        lastNbrs = cur
        // retry isteklih ACK-ova s backoff-om. pending zapis OSTAJE dok ne stigne ACK ili
        // maxAttempts (bug #3: remove-prije-sljanja je gutao ACK koji stigne u istom ticku).
        // Šalje se KOPIJA; zapis čuva attempts/notBefore (sljedeći retry) i svježi next-hop.
        // Eskalacija: nakon 2 neuspjeha unicast prelazi na ograničeni flood (traženje novog puta).
        val due = pending.values.filter { it.notBefore <= at }.toList()
        due.forEach { q ->
            if (q.attempts >= maxAttempts) {
                pending.remove(key(q.frame.src, q.frame.messageId))
                lost.add(key(q.frame.src, q.frame.messageId)); drops["no-ack"] = (drops["no-ack"] ?: 0) + 1
            } else {
                q.attempts++
                q.notBefore = at + ackTimeout * (1L shl q.attempts)
                q.to = if (q.attempts >= 2) null else routes[hex(q.frame.dst)]
                queues[q.frame.priority.code].addLast(q.copy(notBefore = 0))
            }
        }
        // drenaža: SOS preempcija bulk-a + anti-starvation (bulk ≥1/16)
        var budget = txBudgetPerTick
        while (budget > 0) {
            val qi = pickReadyQueue(at) ?: break
            val q = queues[qi].removeFirst()
            if (neighbors.isEmpty()) { stash(q.frame); continue }
            out.add(Tx(Frame.encode(q.frame), q.to)); sent++; budget--
            if (qi == 3) sentSinceBulk = 0 else sentSinceBulk++
            if (q.frame.flags and Frame.FLAG_ACK_REQ != 0 && q.frame.src.bytes.contentEquals(id.bytes)) {
                // vlastiti unicast s ACK_REQ: prati retry ČAK I kao flood bez rute (bug #2:
                // poruka za particionirani čvor je isparavala umjesto da se ponavlja do mergea).
                // Retry-kopije se ne registriraju ponovo (pending zapis već postoji).
                val k = key(q.frame.src, q.frame.messageId)
                if (!pending.containsKey(k) && !acked.contains(k)) {
                    q.notBefore = at + ackTimeout; pending[k] = q
                }
            }
        }
        return out
    }

    private fun readyHead(i: Int, at: Long) = queues[i].isNotEmpty() && queues[i].first().notBefore <= at

    private fun pickReadyQueue(at: Long): Int? {
        // anti-starvation: bulk dobija slot najmanje 1/16 čak i uz stalni SOS promet
        if (readyHead(3, at) && sentSinceBulk >= 16) return 3
        for (i in 0..2) if (readyHead(i, at)) return i // SOS, hitno, normalno: striktno
        if (readyHead(3, at)) return 3
        return null
    }
}
