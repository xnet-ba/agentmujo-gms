package mujo.chat

import mujo.mesh.MeshNode
import mujo.mesh.Identity
import mujo.mesh.Tx
import kotlin.test.*

class ChatTest {
    private fun node(name: String): MeshNode {
        val seed = java.security.MessageDigest.getInstance("SHA-256").digest(("mujo-id:" + name).toByteArray())
        val ident = Identity.deterministic(seed)
        return MeshNode(ident.nodeId(), identity = ident)
    }

    /** Dvosmjerna pumpa dva direktno vezana čvora (bez sim modula). */
    private fun pump(a: MeshNode, b: MeshNode, now: Long) {
        fun txs(n: MeshNode): List<Tx> = n.tick(now)
        for (t in txs(a)) b.receive(t.bytes, a.id, now)
        for (t in txs(b)) a.receive(t.bytes, b.id, now)
    }
    private fun link(a: MeshNode, b: MeshNode) { a.neighbors.add(b.id); b.neighbors.add(a.id) }

    @Test fun groupChat() {
        val a = node("A"); val b = node("B"); link(a, b)
        val ca = ChatService(a); val cb = ChatService(b)
        repeat(5) { pump(a, b, it.toLong()) } // hello razmjena
        ca.sendChat("tim", "krećemo u 6")
        repeat(10) { pump(a, b, (10 + it).toLong()) }
        val got = cb.poll()
        assertEquals(1, got.size); assertEquals("tim", got[0].group); assertEquals("krećemo u 6", got[0].text)
        assertTrue(got[0].from == a.id)
        assertTrue(ca.poll().any { it.group == "tim" }) // vlastiti broadcast i meni
    }

    @Test fun sosAckFlow() {
        val a = node("A"); val b = node("B"); link(a, b)
        val sa = SosManager(a); val sb = SosManager(b)
        var seen: Triple<Double, Double, String>? = null
        sb.onSos = { _, _, lat, lon, text -> seen = Triple(lat, lon, text) }
        repeat(5) { pump(a, b, it.toLong()) }
        val id = sa.startSos("pomoć!", 43.85, 18.35, 5)
        repeat(60) { val t = 6L + it; pump(a, b, t); sa.poll(t); sb.poll(t) }
        assertNotNull(seen); assertEquals(43.85, seen!!.first); assertEquals("pomoć!", seen!!.third)
        assertTrue(sa.session(id)?.done == true, "SOS mora biti potvrđen")
    }

    @Test fun sosGiveUp() {
        val a = node("A"); val b = node("B"); link(a, b)
        val sa = SosManager(a) // B bez poll → niko ne ack-a
        repeat(5) { pump(a, b, it.toLong()) }
        val id = sa.startSos("samoća", 0.0, 0.0, 5)
        repeat(300) { val t = 6L + it; pump(a, b, t); sa.poll(t) }
        val s = sa.session(id)!!
        assertTrue(s.done && s.ackedBy.isEmpty() && s.sent == 10, "max 10 re-emitovanja pa odustaje: sent=${s.sent}")
    }
}
