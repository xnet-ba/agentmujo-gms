package mujo.link

enum class TrafficClass { SOS, CONTROL, CHAT, BULK }

/**
 * Sloj 2, PC-testabilna jezgra (Faza 5a): izbor transporta po susjedu i tipu saobraćaja + fallback.
 * Pravilo (NEVALIDIRANO, mjeri se na uređajima): SOS/CONTROL → najmanja latencija;
 * BULK → najveći throughput; CHAT → najmanja energija. Prvi uspješan šalje, ostalo fallback.
 */
class LinkManager(transports: List<LinkTransport>) {
    private val available = transports.filter { it.isAvailable() }
    private val bindings = mutableMapOf<String, MutableList<Binding>>()
    private val sentOk = mutableMapOf<String, Int>()
    private var failed = 0

    data class Binding(val transport: LinkTransport, val peer: PeerId)

    fun bind(nodeHex: String, transport: LinkTransport, peer: PeerId) {
        bindings.getOrPut(nodeHex) { mutableListOf() }.add(Binding(transport, peer))
    }

    fun send(nodeHex: String, bytes: ByteArray, cls: TrafficClass): Boolean {
        val cands = bindings[nodeHex].orEmpty().filter { it.transport in available }
        val ordered = when (cls) {
            TrafficClass.SOS, TrafficClass.CONTROL ->
                cands.sortedBy { it.transport.costProfile().latencyMs }
            TrafficClass.BULK ->
                cands.sortedByDescending { it.transport.costProfile().throughputKbps }
            TrafficClass.CHAT ->
                cands.sortedBy { it.transport.costProfile().energyPerKbJoule }
        }
        for (b in ordered) {
            val link = b.transport.connect(b.peer)
            if (link == null) { failed++; continue }
            try {
                if (link.send(bytes)) {
                    sentOk[b.transport.id] = (sentOk[b.transport.id] ?: 0) + 1
                    return true
                } else failed++
            } finally { link.close() }
        }
        failed++
        return false
    }

    fun stats(): Map<String, Int> = sentOk.toMap() + ("failed" to failed)
}
