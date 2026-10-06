package mujo.mesh

import kotlin.test.*

// Fino-zrnati unit testovi (bez simulatora). Acceptance scenariji su u sim/Main.kt.
class MeshTest {
    private fun node(name: String): MeshNode {
        val seed = java.security.MessageDigest.getInstance("SHA-256").digest(("mujo-id:" + name).toByteArray())
        val ident = Identity.deterministic(seed)
        return MeshNode(ident.nodeId(), identity = ident)
    }

    @Test fun codecRoundtrip() {
        val a = node("A"); val b = node("B")
        val f = a.signFrame(Frame(type = MsgType.DATA, messageId = ByteArray(16) { it.toByte() },
            src = a.id, dst = b.id,
            priority = Priority.URGENT, ttl = 9, seq = 1, payload = "abc".toByteArray()))
        val rt = Frame.decode(Frame.encode(f))
        assertNotNull(rt); assertEquals("abc", String(rt.payload)); assertEquals(9, rt.ttl); assertEquals(1, rt.seq)
    }

    @Test fun codecDrops() {
        val a = node("A"); val b = node("B")
        val drops = mutableMapOf<String, Int>()
        assertNull(Frame.decode(byteArrayOf(1, 2), drops))
        val badVer = Frame.encode(a.signFrame(Frame(type = MsgType.DATA, messageId = ByteArray(16),
            src = a.id, dst = b.id,
            priority = Priority.NORMAL, ttl = 5, seq = 1))).also { it[0] = 99 }
        assertNull(Frame.decode(badVer, drops))
        assertTrue((drops["short"] ?: 0) > 0 && (drops["version"] ?: 0) > 0)
        val unsigned = Frame.encode(Frame(type = MsgType.DATA, messageId = ByteArray(16),
            src = a.id, dst = b.id, priority = Priority.NORMAL, ttl = 5, seq = 1, payload = "x".toByteArray()))
        assertNull(Frame.decode(unsigned, drops)) // Faza 3: nepotpisan → drop
        assertTrue((drops["no-sig"] ?: 0) > 0)
    }

    @Test fun idempotentDelivery() {
        val a = node("A"); val b = node("B")
        a.neighbors.add(b.id); b.neighbors.add(a.id)
        val raw = Frame.encode(a.signFrame(Frame(type = MsgType.DATA, messageId = ByteArray(16) { 7 },
            src = a.id, dst = b.id, priority = Priority.NORMAL, ttl = 16, seq = 1, payload = "x".toByteArray())))
        b.receive(raw, a.id, 1); b.receive(raw, a.id, 2); b.receive(raw, a.id, 3)
        assertEquals(1, b.delivered.size)
    }

    @Test fun sosRateLimit() {
        val a = node("A"); val b = node("B")
        a.neighbors.add(b.id); b.neighbors.add(a.id)
        repeat(6) { i ->
            val raw = Frame.encode(a.signFrame(Frame(type = MsgType.SOS, messageId = ByteArray(16) { i.toByte() },
                src = a.id, dst = NodeId.BROADCAST, priority = Priority.SOS, ttl = 16, seq = i + 1, payload = "s".toByteArray())))
            b.receive(raw, a.id, i.toLong())
        }
        // 6. SOS u istom prozoru se dropa (4/60 tickova) — ali se i dalje isporučuje primaocu;
        // rate limit koči RELAY, ne isporuku. B prima svih 6, prosljeđuje max 4.
        assertEquals(6, b.delivered.size)
        assertEquals(2, b.drops["sos-ratelimit"] ?: 0)
    }

    @Test fun helloRoundtrip() {
        val h = HelloInfo(73, true, false, 4, 9, NodeId.fromName("C"))
        val rt = HelloInfo.decode(HelloInfo.encode(h))
        assertNotNull(rt); assertEquals(73, rt.battery); assertTrue(rt.charging)
        assertEquals(4, rt.epoch); assertEquals(9, rt.coordSeq)
        assertTrue(rt.coordId!!.bytes.contentEquals(NodeId.fromName("C").bytes))
        assertNull(HelloInfo.decode(ByteArray(5)))
    }

    @Test fun leaseSingleAndTiebreak() {
        val a = NodeId.fromName("A"); val b = NodeId.fromName("B")
        val ta = LeaseTracker(a, leaseTimeout = 60); val tb = LeaseTracker(b, leaseTimeout = 60)
        var ta0 = 0L; while (!ta.tick(ta0) && ta0 < 20) ta0++ // niko: A se proglašava (uz jitter)
        assertNotNull(ta.coord)
        var t = 0L; while (tb.coord == null && t < 20) { tb.tick(t); t++ }
        // B čuje A (epoch viši) → usvaja
        tb.onHello(a, ta.epoch, 1, t)
        assertTrue(tb.coord!!.bytes.contentEquals(a.bytes))
        // ista epoha, manji id pobjeđuje nad već upisanim većim
        tb.onHello(b, ta.epoch, 1, t)
        assertTrue(tb.coord!!.bytes.contentEquals(a.bytes))
    }

    @Test fun leaseStaleCopyExpires() {
        val a = NodeId.fromName("A"); val z = NodeId.fromName("Z")
        val t = LeaseTracker(a, leaseTimeout = 10)
        t.onHello(z, 3, 7, 0) // svježe: seq 7
        t.onHello(z, 3, 7, 5) // ustajala kopija (isti seq): NE osvježava
        var declared = false
        for (tt in 0..30L) if (t.tick(tt)) { declared = true; break }
        assertTrue(declared, "lease mora isteći kad seq ne napreduje")
        val t2 = LeaseTracker(a, leaseTimeout = 10)
        t2.onHello(z, 3, 7, 0)
        t2.onHello(z, 3, 9, 5) // seq napredovao → osvježava
        var declared2 = false
        for (tt in 0..12L) if (t2.tick(tt)) { declared2 = true; break }
        assertFalse(declared2, "svjež seq drži lease živim")
    }

    @Test fun leaseExpiryRedeclares() {
        val a = NodeId.fromName("A")
        val t = LeaseTracker(a, leaseTimeout = 10)
        t.onHello(NodeId.fromName("Z"), 5, 1, 0)
        var declared = false; for (tt in 0..30L) if (t.tick(tt)) { declared = true; break }
        assertTrue(declared); assertEquals(6, t.epoch)
        assertTrue(t.coord!!.bytes.contentEquals(a.bytes))
    }
    @Test fun signVerifyTamper() {
        val a = node("A")
        val f = a.signFrame(Frame(type = MsgType.DATA, messageId = ByteArray(16) { 1 },
            src = a.id, dst = node("B").id, priority = Priority.NORMAL, ttl = 5, seq = 1, payload = "abc".toByteArray()))
        assertTrue(Identity.verify(Identity.parseEdPub(f.edPub)!!, Frame.signBytes(f), f.sig))
        val tampered = f.copy(payload = "abd".toByteArray())
        assertFalse(Identity.verify(Identity.parseEdPub(f.edPub)!!, Frame.signBytes(tampered), f.sig))
        val other = node("Z")
        assertFalse(Identity.verify(other.identity.edPub, Frame.signBytes(f), f.sig))
    }

    @Test fun sealOpenWrongKey() {
        val a = node("A"); val b = node("B"); val z = node("Z")
        val box = Identity.seal("tajna".toByteArray(), b.identity.xPub, a.id.bytes, java.util.Random(1))
        assertFalse(String(box).contains("tajna")) // neproziran na žici
        assertEquals("tajna", String(Identity.open(box, b.identity.xPriv, a.id.bytes)!!))
        assertNull(Identity.open(box, z.identity.xPriv, a.id.bytes)) // pogrešan ključ
        assertNull(Identity.open(box, b.identity.xPriv, z.id.bytes)) // pogrešan AAD (spoof src)
    }
    @Test fun fuzzDecoderNeverThrows() {
        val a = node("A"); val b = node("B")
        val good = Frame.encode(a.signFrame(Frame(type = MsgType.DATA, messageId = ByteArray(16) { 3 },
            src = a.id, dst = b.id, priority = Priority.NORMAL, ttl = 8, seq = 1, payload = "fuzz".toByteArray())))
        val r = java.util.Random(1234)
        var decoded = 0; var dropped = 0
        repeat(2000) {
            val m = good.copyOf()
            repeat(1 + r.nextInt(4)) { m[r.nextInt(m.size)] = r.nextInt(256).toByte() } // 1-4 mutacije
            val drops = mutableMapOf<String, Int>()
            val f = Frame.decode(m, drops) // ne smije baciti
            if (f == null) dropped++ else decoded++
        }
        assertTrue(dropped > 50, "validacija mora paliti (dropped=$dropped)")
        assertEquals(2000, dropped + decoded) // sve klasifikovano, nijednom bačen izuzetak
    }
    @Test fun noAndroidImports() {
        // strukturni gate: core-mesh ne smije referencirati android.* (provjera i u CI greppom)
        val src = java.io.File("src/main/kotlin").walkTopDown().filter { it.isFile }
            .joinToString("\n") { it.readText() }
        assertFalse("android." in src, "core-mesh sadrži android import!")
    }
}
