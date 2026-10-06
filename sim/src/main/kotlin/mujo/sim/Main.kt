package mujo.sim

import mujo.mesh.*

private var failures = 0
private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("PASS $name $detail") else { println("FAIL $name $detail"); failures++ }
}

/** 1. Nestanak relaya: A-B-D + A-C-D, kill B nakon učenja rute → poruka ide preko C, 1x, uz ACK. */
private fun relayFail() {
    val s = SimNet(11); listOf("A", "B", "C", "D").forEach { s.addNode(it) }
    s.link("A", "B"); s.link("B", "D"); s.link("A", "C"); s.link("C", "D")
    val a = s.nodes["A"]!!; val d = s.nodes["D"]!!
    a.sendUnicast(s.idOf("D"), "hi".toByteArray()) // prva poruka nauči rute
    s.run(30)
    val firstOk = d.delivered.count { String(it.payload) == "hi" } == 1 && a.acked.isNotEmpty()
    s.kill("B")
    a.sendUnicast(s.idOf("D"), "hi2".toByteArray())
    s.run(60)
    check("relay-fail/delivery", d.delivered.count { String(it.payload) == "hi2" } == 1,
        "firstOk=$firstOk delivered=${d.delivered.size} lost=${a.lost.size}")
}

/** 2. Partition/merge: A|Z razdvojeni, poruka čeka u storeu, nakon mergea stiže 1x. */
private fun partitionMerge() {
    val s = SimNet(22); listOf("A", "M", "Z").forEach { s.addNode(it) }
    s.link("A", "M"); s.link("M", "Z")
    s.cut("M", "Z") // partition
    val a = s.nodes["A"]!!; val z = s.nodes["Z"]!!
    a.sendUnicast(s.idOf("Z"), "pismo".toByteArray())
    s.run(40)
    val duringPartition = z.delivered.count { String(it.payload) == "pismo" }
    s.link("M", "Z") // merge
    s.run(80)
    check("partition/store-then-deliver", duringPartition == 0 && z.delivered.count { String(it.payload) == "pismo" } == 1,
        "during=$duringPartition after=${z.delivered.count { String(it.payload) == "pismo" }}")
}

/** 3. SOS preempcija: kontinuirano bulk zasićenje, SOS usred toga mora stići unutar par tickova. */
private fun sosPreempt() {
    val s = SimNet(33); listOf("A", "B", "C").forEach { s.addNode(it) }
    s.link("A", "B"); s.link("B", "C")
    val a = s.nodes["A"]!!; val c = s.nodes["C"]!!
    var sosTick = -1L; var sosInject = -1L
    repeat(120) { t ->
        repeat(3) { a.sendUnicast(s.idOf("C"), ByteArray(200) { it.toByte() }, Priority.BULK, ackReq = false) } // stalno zasićenje
        if (t == 10) { a.broadcast("SOS!".toByteArray(), sos = true); sosInject = s.now }
        s.step()
        if (sosTick < 0 && c.delivered.any { it.type == MsgType.SOS }) sosTick = s.now
    }
    val bulkInFlight = a.delivered.size // nebitno; bitno: SOS preskočio red
    check("sos/preempt", sosTick >= 0 && sosTick - sosInject <= 8,
        "sosLat=${if (sosTick >= 0) sosTick - sosInject else -1} tickova uz puno bulk redove")
}

/** 4. Broadcast storm: 20 čvorova, svaki relay max 1x, svi prime 1x. */
private fun storm() {
    val s = SimNet(44); val n = 20
    repeat(n) { s.addNode("N$it") }
    val r = s.rng
    repeat(60) { val a = r.nextInt(n); var b = r.nextInt(n); if (a != b) try { s.link("N$a", "N$b") } catch (_: Exception) {} }
    // garantiraj povezanost: lanac
    repeat(n - 1) { try { s.link("N$it", "N${it + 1}") } catch (_: Exception) {} }
    s.nodes["N0"]!!.broadcast("bcast".toByteArray())
    s.run(120)
    val counts = (0 until n).map { s.nodes["N$it"]!!.delivered.count { String(it.payload) == "bcast" } }
    val overRelay = s.nodes.values.any { it.forwarded > 1 }
    check("storm/exactly-once", counts.all { it == 1 }, "counts=${counts.toSet()} txTotal=${s.txTotal}")
    check("storm/no-reflood", !overRelay, "")
}

/** 5. Idempotentnost: ista sirova kopija 3x → aplikaciji 1x. */
private fun idem() {
    val s = SimNet(55); listOf("A", "B").forEach { s.addNode(it) }; s.link("A", "B")
    val a = s.nodes["A"]!!; val b = s.nodes["B"]!!
    a.sendUnicast(s.idOf("B"), "once".toByteArray(), ackReq = false)
    s.run(10)
    val raw = Frame.encode(a.signFrame(Frame(type = MsgType.DATA, messageId = ByteArray(16) { 7 }, src = a.id,
        dst = b.id, priority = Priority.NORMAL, ttl = 16, seq = 50, payload = "dup".toByteArray())))
    b.receive(raw, a.id, 100); b.receive(raw, a.id, 101); b.receive(raw, a.id, 102)
    check("idem/once", b.delivered.count { String(it.payload) == "dup" } == 1, "")
}

/** 6. Codec roundtrip + drop pravila (ver/tip/skraćeno/nepotpisan). */
private fun codec() {
    val s = SimNet(56); listOf("X", "Y").forEach { s.addNode(it) }
    val x = s.nodes["X"]!!; val y = s.nodes["Y"]!!
    val f = x.signFrame(Frame(type = MsgType.DATA, messageId = ByteArray(16) { 1 }, src = x.id,
        dst = y.id, priority = Priority.URGENT, ttl = 9, seq = 3, payload = "abc".toByteArray()))
    val rt = Frame.decode(Frame.encode(f))
    val bad1 = Frame.decode(byteArrayOf(9, 1, 2)) // kratak
    val good = Frame.encode(f).copyOf().also { it[0] = 99 } // pogrešan ver
    check("codec/roundtrip", rt != null && String(rt.payload) == "abc" && rt.ttl == 9 && rt.seq == 3, "")
    check("codec/drops", bad1 == null && Frame.decode(good) == null, "")
}

/** 7. Mreža 24 čvora, gubici 5%: unicast svima uz seed → nema duplikata, SOS svima stiže. */
private fun scale() {
    val s = SimNet(77); val n = 24
    repeat(n) { s.addNode("S$it") }
    val r = s.rng
    repeat(n - 1) { s.link("S$it", "S${it + 1}", loss = 0.05) } // kičma bez particije
    repeat(40) { val a = r.nextInt(n); var b = r.nextInt(n); if (a != b) try { s.link("S$a", "S$b", loss = 0.05) } catch (_: Exception) {} }
    val src = s.nodes["S0"]!!
    for (i in 1 until n) src.sendUnicast(s.idOf("S$i"), "m$i".toByteArray())
    s.run(400)
    var dups = 0; var missing = 0
    for (i in 1 until n) {
        val c = s.nodes["S$i"]!!.delivered.count { String(it.payload) == "m$i" }
        if (c == 0) missing++ else if (c > 1) dups++
    }
    src.broadcast("SOS-ALL".toByteArray(), sos = true); s.run(200)
    val sosGot = (0 until n).count { s.nodes["S$it"]!!.delivered.any { f -> f.type == MsgType.SOS } }
    check("scale/no-dup", dups == 0, "dups=$dups missing=$missing (gubici 5% → missing može biti >0, retry limit 4)")
    check("scale/sos-all", sosGot == n, "sosGot=$sosGot/$n")
}

fun main() {
    relayFail(); partitionMerge(); sosPreempt(); storm(); idem(); codec(); scale()
    coordKill(); partitionMergeCoord(); recoveryTime(); batteryGate(); batteryPrefer()
    e2eChat(); spoofDrop(); replayDrop()
    stormLarge(); partitionFlap()
    println(if (failures == 0) "ALL PASS" else "$failures FAILURES")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** Faza 2: koordinator izabran, kill → novi unutar limita, uvijek tačno 1. */
private fun coordKill() {
    val s = SimNet(101); val n = 6
    repeat(n) { s.addNode("K$it") }
    for (i in 0 until n) for (j in i + 1 until n) try { s.link("K$i", "K$j") } catch (_: Exception) {}
    s.run(120)
    val c0 = s.nodes.values.mapNotNull { it.tracker.coord }.mapNotNull { s.nameOf(it) }.toSet()
    check("coord/initial-single", c0.size == 1, "c0=$c0")
    val victim = c0.first()
    s.kill(victim) // ubij aktuelnog koordinatora (koji god bio)
    s.run(250)
    val alive = s.nodes.filterKeys { it != victim }.values
    val coords = alive.mapNotNull { it.tracker.coord }.mapNotNull { s.nameOf(it) }.toSet()
    check("coord/kill-reelect", coords.size == 1 && victim !in coords,
        "killed=$victim after=$coords")
}

/** Faza 2: partition → 2 koordinatora (privremeno OK), merge → opet 1, poruke bez gubitaka/duplikata. */
private fun partitionMergeCoord() {
    val s = SimNet(102)
    repeat(6) { s.addNode("P$it") }
    repeat(5) { s.link("P$it", "P${it + 1}") }
    s.link("P0", "P2"); s.link("P3", "P5")
    s.run(120)
    s.cut("P2", "P3") // partition 0-2 | 3-5
    val a = s.nodes["P0"]!!
    repeat(3) { a.sendUnicast(s.idOf("P5"), "pm$it".toByteArray()) }
    s.run(150) // strana B bira svog (lease istekne)
    val duringB = s.nodes["P5"]!!.tracker.coord?.let { s.nameOf(it) }
    s.link("P2", "P3") // merge
    s.run(250)
    val coords = s.nodes.values.mapNotNull { it.tracker.coord }.mapNotNull { s.nameOf(it) }.toSet()
    var dups = 0; var missing = 0
    for (i in 0..2) {
        val c = s.nodes["P5"]!!.delivered.count { String(it.payload) == "pm$i" }
        if (c == 0) missing++ else if (c > 1) dups++
    }
    check("coord/partition-merge", coords.size == 1 && missing == 0 && dups == 0,
        "coords=${coords.size} duringB=$duringB missing=$missing dups=$dups")
}

/** Faza 2: utišani relay → timeout invalidira rutu, saobraćaj se vraća preko C; mjeri oporavak. */
private fun recoveryTime() {
    val s = SimNet(103); listOf("A", "B", "C", "D").forEach { s.addNode(it) }
    s.link("A", "B"); s.link("B", "D"); s.link("A", "C"); s.link("C", "D")
    val a = s.nodes["A"]!!; val d = s.nodes["D"]!!
    a.sendUnicast(s.idOf("D"), "warm".toByteArray()); s.run(60)
    s.quiet("B") // B ćuti ali veze stoje → timeout (ne kill)
    var recoveredAt = -1L
    var msgN = 0
    repeat(150) {
        if (it % 5 == 0) { a.sendUnicast(s.idOf("D"), "r${msgN++}".toByteArray()); }
        s.step()
        if (recoveredAt < 0 && d.delivered.any { String(it.payload).startsWith("r") }) recoveredAt = s.now
    }
    val viaB = a.routes[s.idOf("D").bytes.joinToString("") { "%02x".format(it) }]?.next
    val viaBName = viaB?.let { s.nameOf(it) }
    check("heal/recovery", recoveredAt >= 0 && recoveredAt - 60 <= 60 && viaBName != "B",
        "recoveryTick=$recoveredAt via=$viaBName (očekivano ≤60 tickova od quiet)")
}

/** Faza 2: kritična baterija ne relayuje bulk, ali SOS prolazi. */
private fun batteryGate() {
    val s = SimNet(104); listOf("A", "B", "C").forEach { s.addNode(it) }
    s.link("A", "B"); s.link("B", "C")
    s.nodes["B"]!!.battery = 5
    val a = s.nodes["A"]!!; val c = s.nodes["C"]!!; val b = s.nodes["B"]!!
    a.sendUnicast(s.idOf("C"), "bulk-x".toByteArray())
    a.broadcast("SOS-B".toByteArray(), sos = true)
    s.run(150)
    val bulkGot = c.delivered.any { String(it.payload) == "bulk-x" }
    val sosGot = c.delivered.any { it.type == MsgType.SOS }
    check("battery/gate", !bulkGot && sosGot && (b.drops["battery-skip"] ?: 0) > 0,
        "bulk=$bulkGot sos=$sosGot skips=${b.drops["battery-skip"]}")
}

/** Faza 3: E2E unicast kroz relay — primalac čita plaintext, relay ne može. */
private fun e2eChat() {
    val s = SimNet(106); listOf("A", "B", "C").forEach { s.addNode(it) }
    s.link("A", "B"); s.link("B", "C")
    val a = s.nodes["A"]!!; val b = s.nodes["B"]!!; val c = s.nodes["C"]!!
    c.broadcast("beacon".toByteArray()); s.run(30) // otkrivanje ključeva kroz overheard saobraćaj
    a.sendUnicast(s.idOf("C"), "tajna".toByteArray()) // e2e po defaultu
    s.run(80)
    val plain = c.delivered.filter { String(it.payload) == "tajna" }
    val leak = b.delivered.any { try { String(it.payload) == "tajna" } catch (_: Exception) { false } }
    check("e2e/chat", plain.size == 1 && !leak && (a.drops["no-e2e-key"] ?: 0) == 0,
        "plain=${plain.size} leak=$leak noKey=${a.drops["no-e2e-key"]}")
}

/** Faza 3: spoofing (tuđi ključ / tuđi potpis) umire na prvom hopu. */
private fun spoofDrop() {
    val s = SimNet(107); listOf("A", "B").forEach { s.addNode(it) }; s.link("A", "B")
    val a = s.nodes["A"]!!; val b = s.nodes["B"]!!
    val z = mujo.mesh.Identity.deterministic(ByteArray(32) { 9 })
    // lažni ključ uz A-ov id
    val f1 = Frame(type = MsgType.DATA, messageId = Frame.randomId(s.rng), src = a.id, dst = b.id,
        priority = Priority.NORMAL, ttl = 16, seq = 99, edPub = z.edPubBytes, xPub = z.xPubBytes, payload = "laz".toByteArray())
    val f1s = f1.copy(sig = z.sign(Frame.signBytes(f1)))
    b.receive(Frame.encode(f1s), a.id, 50)
    // A-ov ključ ali Z-ov potpis
    val f2 = a.signFrame(Frame(type = MsgType.DATA, messageId = Frame.randomId(s.rng), src = a.id, dst = b.id,
        priority = Priority.NORMAL, ttl = 16, seq = 100, payload = "laz2".toByteArray()))
    val f2s = f2.copy(sig = z.sign(Frame.signBytes(f2.copy(sig = ByteArray(0)))))
    b.receive(Frame.encode(f2s), a.id, 51)
    check("spoof/drop", b.delivered.isEmpty() && (b.drops["id-spoof"] ?: 0) == 1 && (b.drops["bad-sig"] ?: 0) == 1,
        "drops=${b.drops}")
}

/** Faza 3: replay starog seq-a se dropa, reorder unutar prozora (8) prolazi. */
private fun replayDrop() {
    val s = SimNet(108); listOf("A", "B").forEach { s.addNode(it) }; s.link("A", "B")
    val a = s.nodes["A"]!!; val b = s.nodes["B"]!!
    repeat(10) { a.sendUnicast(s.idOf("B"), "m$it".toByteArray()) }
    s.run(60) // B.lastSeq[A] = 10
    fun craft(seq: Int, tag: String): ByteArray {
        val f = a.signFrame(Frame(type = MsgType.DATA, messageId = Frame.randomId(s.rng), src = a.id,
            dst = b.id, priority = Priority.NORMAL, ttl = 16, seq = seq, payload = tag.toByteArray()))
        return Frame.encode(f)
    }
    b.receive(craft(1, "m1-again"), a.id, 200) // star (1 <= 10-8) → replay
    b.receive(craft(9, "m9-late"), a.id, 201)  // unutar prozora → primljen (reorder tolerancija)
    check("replay/drop", b.delivered.none { String(it.payload) == "m1-again" } &&
        b.delivered.any { String(it.payload) == "m9-late" } && (b.drops["replay"] ?: 0) == 1,
        "drops=${b.drops}")
}
/** Oluja na 40 čvorova: tačno-1x isporuka, bez reflooda — skala flooding granice. */
private fun stormLarge() {
    val s = SimNet(109); val n = 40
    repeat(n) { s.addNode("L$it") }
    val r = s.rng
    repeat(140) { val a = r.nextInt(n); var b = r.nextInt(n); if (a != b) try { s.link("L$a", "L$b") } catch (_: Exception) {} }
    repeat(n - 1) { try { s.link("L$it", "L${it + 1}") } catch (_: Exception) {} }
    s.nodes["L0"]!!.broadcast("velika".toByteArray())
    s.run(250)
    val counts = (0 until n).map { s.nodes["L$it"]!!.delivered.count { String(it.payload) == "velika" } }
    check("storm40/exactly-once", counts.all { it == 1 }, "miss=${counts.count { it == 0 }} dups=${counts.count { it > 1 }}")
    check("storm40/no-reflood", s.nodes.values.all { it.forwarded <= 1 }, "")
}

/** Flapping particije 5x uz saobraćaj: bez duplikata i gubitaka (store-forward robustnost). */
private fun partitionFlap() {
    val s = SimNet(110); listOf("A", "M", "Z").forEach { s.addNode(it) }
    s.link("A", "M"); s.link("M", "Z")
    val a = s.nodes["A"]!!; val z = s.nodes["Z"]!!
    repeat(5) { a.sendUnicast(s.idOf("Z"), "fl$it".toByteArray()) }
    repeat(5) { s.cut("M", "Z"); s.run(15); s.link("M", "Z"); s.run(30) }
    s.run(100)
    var dups = 0; var missing = 0
    for (i in 0..4) {
        val c = z.delivered.count { String(it.payload) == "fl$i" }
        if (c == 0) missing++ else if (c > 1) dups++
    }
    check("flap/no-loss-no-dup", missing == 0 && dups == 0, "missing=$missing dups=$dups")
}
/** Faza 2: greedy min-cost — A bira C (90%) preko B (10%) za rutu do D. */
private fun batteryPrefer() {
    val s = SimNet(105); listOf("A", "B", "C", "D").forEach { s.addNode(it) }
    s.link("A", "B"); s.link("B", "D"); s.link("A", "C"); s.link("C", "D")
    s.nodes["B"]!!.battery = 10; s.nodes["C"]!!.battery = 90
    s.nodes["D"]!!.broadcast("beacon".toByteArray()) // D se čuje preko oba puta
    s.run(80)
    val a = s.nodes["A"]!!
    val next = a.routes[s.idOf("D").bytes.joinToString("") { "%02x".format(it) }]?.next
    check("battery/prefer", next != null && s.nameOf(next) == "C",
        "next=${next?.let { s.nameOf(it) }}")
}
