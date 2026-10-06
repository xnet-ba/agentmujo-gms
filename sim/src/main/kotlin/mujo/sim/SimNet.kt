package mujo.sim

import mujo.mesh.*
import java.util.Random

data class Link(var loss: Double = 0.0, var latency: Int = 1)
private data class InFlight(val at: Long, val to: String, val bytes: ByteArray, val from: NodeId)

/** Deterministička virtualna mreža: gubici, latencija, partition, kill. */
class SimNet(seed: Long) {
    val rng = Random(seed)
    val nodes = mutableMapOf<String, MeshNode>()
    val links = mutableMapOf<Pair<String, String>, Link>()
    private val inflight = mutableListOf<InFlight>()
    var now: Long = 0
    var txTotal = 0
    val dead = mutableSetOf<String>()

    fun addNode(name: String): MeshNode {
        val n = MeshNode(NodeId.fromName(name), rng = Random(rng.nextLong()))
        nodes[name] = n; return n
    }
    fun link(a: String, b: String, loss: Double = 0.0, latency: Int = 1) {
        links[minOf(a, b) to maxOf(a, b)] = Link(loss, latency)
        nodes[a]!!.neighbors.add(nodes[b]!!.id); nodes[b]!!.neighbors.add(nodes[a]!!.id)
    }
    fun cut(a: String, b: String) {
        links.remove(minOf(a, b) to maxOf(a, b))
        nodes[a]?.neighbors?.remove(nodes[b]?.id); nodes[b]?.neighbors?.remove(nodes[a]?.id)
    }
    fun kill(name: String) { dead.add(name); links.keys.filter { it.first == name || it.second == name }.toList().forEach { links.remove(it) }; nodes.values.forEach { it.neighbors.remove(nodes[name]!!.id) }; nodes[name]!!.neighbors.clear() }
    fun revive(name: String, to: List<String>) { dead.remove(name); to.forEach { link(name, it) } }
    fun idOf(name: String) = nodes[name]!!.id

    fun nameOf(id: NodeId) = nodes.entries.firstOrNull { it.value.id.bytes.contentEquals(id.bytes) }?.key

    private fun transmit(fromName: String, tx: Tx) {
        val n = nodes[fromName]!!
        txTotal++
        val targets = if (tx.to == null) n.neighbors.toList()
            else listOfNotNull(tx.to).filter { n.neighbors.contains(it) }
        for (t in targets) {
            val tname = nameOf(t) ?: continue
            if (tname in dead) continue
            val l = links[minOf(fromName, tname) to maxOf(fromName, tname)] ?: continue
            if (rng.nextDouble() < l.loss) continue
            inflight.add(InFlight(now + l.latency, tname, tx.bytes, n.id))
        }
    }

    fun step() {
        for ((name, n) in nodes) {
            if (name in dead) continue
            for (tx in n.tick(now)) transmit(name, tx)
        }
        val due = inflight.filter { it.at <= now }.toList()
        inflight.removeAll(due.toSet())
        for (d in due) {
            if (d.to in dead) continue
            // receive() može vratiti izravne Tx (npr. ACK): oni MORAJU u mrežu istim putem
            for (tx in nodes[d.to]!!.receive(d.bytes, d.from, now)) {
                val fromName = d.to
                if (fromName !in dead) transmit(fromName, tx)
            }
        }
        now++
    }
    fun run(ticks: Int) = repeat(ticks) { step() }
}
