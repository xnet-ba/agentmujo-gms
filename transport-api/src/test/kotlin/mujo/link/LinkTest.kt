package mujo.link

import kotlin.test.*

class LinkTest {
    @Test fun loopbackSendReceive() {
        val hub = LoopbackHub()
        val pa = PeerId("A"); val pb = PeerId("B")
        val ta = LoopbackTransport("A", hub) { setOf(pb) }
        val tb = LoopbackTransport("B", hub) { setOf(pa) }
        val gotB = mutableListOf<ByteArray>(); val gotA = mutableListOf<ByteArray>()
        ta.setReceiver { _, b -> gotA.add(b) }; tb.setReceiver { _, b -> gotB.add(b) }
        val found = mutableListOf<PeerId>()
        ta.startDiscovery { found.add(it) }
        assertEquals(listOf(pb), found)
        val link = ta.connect(pb); assertNotNull(link)
        assertNull(ta.connect(PeerId("ghost"))) // nepoznat peer
        assertTrue(link.send("zdravo".toByteArray()))
        tb.pump(); ta.pump()
        assertEquals(1, gotB.size); assertEquals("zdravo", String(gotB[0])); assertTrue(gotA.isEmpty())
        link.close(); assertFalse(link.send("x".toByteArray()))
    }

    @Test fun probeModels() {
        val f = FakeProbe.flagship(); val o = FakeProbe.oldPhone()
        assertTrue("aware" in f.probe().transports && f.probe().bleAdvertising)
        assertTrue("aware" !in o.probe().transports && !o.probe().gps)
        // stari telefon graceful: samo dio transporta
        assertTrue(o.probe().transports.isNotEmpty())
    }

    @Test fun noAndroidImports() {
        val src = java.io.File("src/main/kotlin").walkTopDown().filter { it.isFile }
            .joinToString("\n") { it.readText() }
        assertFalse("import android" in src, "transport-api sadrži android import!")
    }
}
