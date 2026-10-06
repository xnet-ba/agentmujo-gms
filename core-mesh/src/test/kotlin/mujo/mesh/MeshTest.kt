package mujo.mesh

import kotlin.test.*

// Fino-zrnati unit testovi (bez simulatora). Acceptance scenariji su u sim/Main.kt.
class MeshTest {
    private fun node(name: String) = MeshNode(NodeId.fromName(name))

    @Test fun codecRoundtrip() {
        val f = Frame(type = MsgType.DATA, messageId = ByteArray(16) { it.toByte() },
            src = NodeId.fromName("A"), dst = NodeId.fromName("B"),
            priority = Priority.URGENT, ttl = 9, payload = "abc".toByteArray())
        val rt = Frame.decode(Frame.encode(f))
        assertNotNull(rt); assertEquals("abc", String(rt.payload)); assertEquals(9, rt.ttl)
    }

    @Test fun codecDrops() {
        val drops = mutableMapOf<String, Int>()
        assertNull(Frame.decode(byteArrayOf(1, 2), drops))
        val badVer = Frame.encode(Frame(type = MsgType.DATA, messageId = ByteArray(16),
            src = NodeId.fromName("A"), dst = NodeId.fromName("B"),
            priority = Priority.NORMAL, ttl = 5)).also { it[0] = 99 }
        assertNull(Frame.decode(badVer, drops))
        assertTrue((drops["short"] ?: 0) > 0 && (drops["version"] ?: 0) > 0)
    }

    @Test fun idempotentDelivery() {
        val a = node("A"); val b = node("B")
        a.neighbors.add(b.id); b.neighbors.add(a.id)
        val raw = Frame.encode(Frame(type = MsgType.DATA, messageId = ByteArray(16) { 7 },
            src = a.id, dst = b.id, priority = Priority.NORMAL, ttl = 16, payload = "x".toByteArray()))
        b.receive(raw, a.id, 1); b.receive(raw, a.id, 2); b.receive(raw, a.id, 3)
        assertEquals(1, b.delivered.size)
    }

    @Test fun sosRateLimit() {
        val a = node("A"); val b = node("B")
        a.neighbors.add(b.id); b.neighbors.add(a.id)
        repeat(6) { i ->
            val raw = Frame.encode(Frame(type = MsgType.SOS, messageId = ByteArray(16) { i.toByte() },
                src = a.id, dst = NodeId.BROADCAST, priority = Priority.SOS, ttl = 16, payload = "s".toByteArray()))
            b.receive(raw, a.id, i.toLong())
        }
        // 6. SOS u istom prozoru se dropa (4/60 tickova) — ali se i dalje isporučuje primaocu;
        // rate limit koči RELAY, ne isporuku. B prima svih 6, prosljeđuje max 4.
        assertEquals(6, b.delivered.size)
        assertEquals(2, b.drops["sos-ratelimit"] ?: 0)
    }

    @Test fun noAndroidImports() {
        // strukturni gate: core-mesh ne smije referencirati android.* (provjera i u CI greppom)
        val src = java.io.File("src/main/kotlin").walkTopDown().filter { it.isFile }
            .joinToString("\n") { it.readText() }
        assertFalse("android." in src, "core-mesh sadrži android import!")
    }
}
