package mujo.agent

import mujo.mesh.Identity
import mujo.mesh.MeshNode
import kotlin.test.*

class AgentTest {
    private fun node(name: String, battery: Int = 100): MeshNode {
        val seed = java.security.MessageDigest.getInstance("SHA-256").digest(("mujo-id:" + name).toByteArray())
        val ident = Identity.deterministic(seed)
        return MeshNode(ident.nodeId(), identity = ident, battery = battery)
    }

    @Test fun profilesSane() {
        assertEquals(5, Profiles.ALL.size)
        Profiles.ALL.forEach {
            assertTrue(it.heartbeatIntervalTicks > 0 && it.sosQuorum >= 1 && it.sosMaxTries >= 1)
            assertTrue(it.note.isNotEmpty())
        }
    }

    @Test fun batteryOffAndBack() {
        val n = node("A", battery = 20); val ag = NetworkAgent(n, Profiles.WILD) // prag 25, iznad mesh hard-gatea 15
        repeat(50) { ag.tick(it.toLong()) } // prvih 50: samo report nesigurnosti
        assertTrue(ag.log.any { it.reason.contains("nemam dovoljno mjerenja") })
        assertFalse(n.manualNoRelay)
        ag.tick(50)
        assertTrue(n.manualNoRelay, "agent gasi relay na niskoj bateriji")
        assertTrue(ag.log.any { it.action == "set-norelay" })
        n.battery = 90; n.charging = true
        repeat(40) { ag.tick((100 + it).toLong()) } // rate limit prođe
        assertFalse(n.manualNoRelay, "agent vraća relay kad se baterija oporavi")
    }

    @Test fun rateLimit() {
        // alternacija low/high svakih 5 tickova bez limitera dala bi ~40 akcija; limiter (30) reže na ≈7
        val p = AgentProfile("t", 10, false, 20, 1, 20, 10, 64, "test")
        val n = node("A", battery = 18); val ag = NetworkAgent(n, p)
        var t = 50L
        val acts = mutableListOf<String>()
        repeat(40) { i ->
            if (i % 2 == 0) { n.battery = 18; n.charging = false } else { n.battery = 90; n.charging = true }
            repeat(5) { acts += ag.tick(t++).map { d -> d.action } }
        }
        val nActions = acts.count { it == "set-norelay" || it == "set-relay" }
        assertTrue(nActions in 2..8, "rate limit: $nActions akcija (bez limitera ~40)")
    }

    @Test fun isolatedHonest() {
        val n = node("A"); val ag = NetworkAgent(n)
        val ds = (50..55).flatMap { ag.tick(it.toLong()) }
        assertTrue(ds.any { it.action == "report" && it.reason.contains("izolovan") })
        assertFalse(n.manualNoRelay, "izolacija ne gasi ništa — samo izvještaj")
    }

    @Test fun meshWorksWithoutAgent() {
        // agent je posmatrač: delivery ne zavisi od njega (dimni test: poruka kroz relay bez ikakvog agenta)
        val a = node("A"); val b = node("B"); val c = node("C")
        a.neighbors.add(b.id); b.neighbors.add(a.id); b.neighbors.add(c.id); c.neighbors.add(b.id)
        var t = 0L
        fun pump() { for (tx in a.tick(t)) b.receive(tx.bytes, a.id, t); for (tx in b.tick(t)) { a.receive(tx.bytes, b.id, t); c.receive(tx.bytes, b.id, t) }; for (tx in c.tick(t)) b.receive(tx.bytes, c.id, t); t++ }
        repeat(10) { pump() }
        a.sendUnicast(c.id, "bez-agenta".toByteArray())
        repeat(40) { pump() }
        assertTrue(c.delivered.any { String(it.payload) == "bez-agenta" })
    }
}
