package mujo.link

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process loopback par za testove: dva kraja, direktna isporuka kroz [mediator].
 * Simulira jedan bidirekcioni link (gubitak/latencija se dodaju u simu, ne ovdje).
 */
class LoopbackHub {
    private val boxes = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<PeerId, ByteArray>>>()
    fun deliver(to: String, from: PeerId, bytes: ByteArray) {
        boxes.computeIfAbsent(to) { CopyOnWriteArrayList() }.add(from to bytes)
    }
    fun drain(addr: String): List<Pair<PeerId, ByteArray>> = boxes.remove(addr) ?: emptyList()
}

class LoopbackTransport(
    private val addr: String,
    private val hub: LoopbackHub,
    private val profile: CostProfile = CostProfile(energyPerKbJoule = 0.1, throughputKbps = 1000.0, latencyMs = 1.0, mtuBytes = 512),
    override val id: String = "loopback",
    private val peers: () -> Set<PeerId> = { emptySet() },
) : LinkTransport {
    private var cb: ((PeerId, ByteArray) -> Unit)? = null
    private var discovering = false

    override fun isAvailable() = true
    override fun startDiscovery(onPeer: (PeerId) -> Unit) { discovering = true; peers().forEach(onPeer) }
    override fun stopDiscovery() { discovering = false }
    override fun connect(peer: PeerId): LinkTransport.Link? =
        if (!discovering || peer !in peers()) null else LoopbackLink(peer)
    override fun setReceiver(cb: (PeerId, ByteArray) -> Unit) { this.cb = cb }
    override fun linkQuality(peer: PeerId) = LinkQuality(lossEstimate = 0.0, mtuBytes = 512)
    override fun costProfile() = profile

    /** Ispumpaj pristiglo (test harness zove nakon slanja). */
    fun pump() { hub.drain(addr).forEach { (from, bytes) -> cb?.invoke(from, bytes) } }

    private inner class LoopbackLink(override val peer: PeerId) : LinkTransport.Link {
        private var open = true
        override fun send(bytes: ByteArray): Boolean {
            if (!open) return false
            hub.deliver(peer.address, PeerId(addr), bytes); return true
        }
        override fun close() { open = false }
    }
}
