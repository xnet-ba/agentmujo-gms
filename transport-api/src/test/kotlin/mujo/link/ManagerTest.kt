package mujo.link

import kotlin.test.*

class ManagerTest {
    private fun loop(id: String, profile: CostProfile, hub: LoopbackHub, peer: PeerId) =
        LoopbackTransport(id, hub, profile, id) { setOf(peer) }.also { it.startDiscovery {} }

    @Test fun picksByTrafficClass() {
        val hub = LoopbackHub(); val peer = PeerId("P")
        val ble = loop("ble", CostProfile(0.1, 50.0, 30.0, 128), hub, peer) // štedljiv, spor
        val wifi = loop("wifi", CostProfile(1.0, 5000.0, 5.0, 1400), hub, peer) // brz, troši
        val m = LinkManager(listOf(ble, wifi))
        m.bind("N", ble, peer); m.bind("N", wifi, peer)
        assertTrue(m.send("N", "sos".toByteArray(), TrafficClass.SOS)) // wifi: latencija 5 < 30
        assertTrue(m.send("N", "hi".toByteArray(), TrafficClass.CHAT)) // ble: energija 0.1 < 1.0
        assertTrue(m.send("N", ByteArray(100), TrafficClass.BULK)) // wifi: throughput
        val st = m.stats()
        assertEquals(1, st["ble"]); assertEquals(2, st["wifi"])
    }

    @Test fun fallbackAndUnknown() {
        val hub = LoopbackHub(); val peer = PeerId("P")
        val dead = LoopbackTransport("dead", hub, CostProfile(0.0, 999999.0, 0.0, 9), "dead") { emptySet() }
            .also { it.startDiscovery {} } // najbolji na papiru, ali nema peerove → connect null
        val good = loop("good", CostProfile(5.0, 10.0, 99.0, 9), hub, peer)
        val m = LinkManager(listOf(dead, good))
        m.bind("N", dead, peer); m.bind("N", good, peer)
        assertTrue(m.send("N", "x".toByteArray(), TrafficClass.BULK)) // fallback na good
        assertFalse(m.send("ghost", "x".toByteArray(), TrafficClass.CHAT)) // nepoznat čvor
        assertTrue((m.stats()["failed"] ?: 0) >= 2)
    }
}
