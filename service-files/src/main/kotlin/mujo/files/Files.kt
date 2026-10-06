package mujo.files

import mujo.mesh.MeshNode
import mujo.mesh.NodeId
import mujo.mesh.Priority
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Fajlovi/fotografije preko mesha (Faza 7a, čisti Kotlin): chunking, nastavak, kompresija, nizak prioritet.
 * Pouzdanost nose mesh ACK/retry; ovdje manifest + bitmap primljenih + FILE_REQ za rupe + sha256 provjera.
 */
object FileFrame {
    const val MANIFEST: Byte = 0x30
    const val CHUNK: Byte = 0x31
    const val REQ: Byte = 0x32
    const val DONE: Byte = 0x33
}

class FileTransfer(val node: MeshNode, val chunkSize: Int = 1024) {
    sealed interface Event { data class Received(val name: String, val bytes: ByteArray) : Event; data class Progress(val fileHex: String, val got: Int, val total: Int) : Event }
    private data class Out(val dst: NodeId, val name: String, val chunks: List<ByteArray>, val hash: ByteArray,
        val compressed: Boolean, var manifestAt: Long = 0, var done: Boolean = false, var idx: Int = 0)
    private data class Incoming(val dst: NodeId, val name: String, val total: Int, val hash: ByteArray,
        val compressed: Boolean, val buf: MutableMap<Int, ByteArray> = mutableMapOf(), var lastActive: Long = 0)

    private val out = mutableMapOf<String, Out>()
    private val incoming = mutableMapOf<String, Incoming>()
    private val completed = mutableSetOf<String>() // primljeni do kraja: MANIFEST→samo DONE ponovo, nikad re-download (bug #12: dupli Received)
    private var consumed = 0
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val rnd = java.util.Random()

    fun offerFile(dst: NodeId, name: String, bytes: ByteArray, compress: Boolean, now: Long): ByteArray {
        var body = bytes; var c = false
        if (compress) {
            val d = Deflater(Deflater.BEST_SPEED); d.setInput(bytes); d.finish()
            val tmp = ByteArray(bytes.size); val n = d.deflate(tmp); d.end()
            if (n < bytes.size) { body = tmp.copyOf(n); c = true }
        }
        val id = ByteArray(16).also { rnd.nextBytes(it) }
        val chunks = body.toList().chunked(chunkSize).map { it.toByteArray() }
        val o = Out(dst, name, chunks, MessageDigest.getInstance("SHA-256").digest(body), c)
        out[hex(id)] = o; sendManifest(id, o, now)
        o.idx = 0
        return id
    }

    private fun sendManifest(id: ByteArray, o: Out, now: Long) {
        val nb = o.name.toByteArray()
        val b = ByteBuffer.allocate(1 + 16 + 4 + 4 + 32 + 1 + nb.size)
        b.put(FileFrame.MANIFEST); b.put(id); b.putInt(o.chunks.size); b.putInt(o.chunks.sumOf { it.size })
        b.put(o.hash); b.put(if (o.compressed) 1 else 0); b.put(nb)
        node.sendUnicast(o.dst, b.array(), Priority.URGENT, ackReq = true, e2e = false)
        o.manifestAt = now
    }

    fun outChunks(): Map<String, Int> = out.mapValues { it.value.chunks.size }

    fun poll(now: Long): List<Event> {
        val ev = mutableListOf<Event>()
        while (consumed < node.delivered.size) {
            val f = node.delivered[consumed++]
            val p = f.payload; if (p.isEmpty()) continue
            when (p[0]) {
                FileFrame.MANIFEST -> if (p.size >= 58) {
                    val b = ByteBuffer.wrap(p, 1, p.size - 1)
                    val id = ByteArray(16); b.get(id)
                    val total = b.int; val size = b.int; val h = ByteArray(32); b.get(h)
                    val comp = b.get().toInt() != 0
                    val name = String(p, 58, p.size - 58)
                    if (hex(id) in completed) { sendDone(id, f.src); continue }
                    val inc = incoming.getOrPut(hex(id)) { Incoming(f.src, name, total, h, comp, lastActive = now) }
                    inc.lastActive = now
                    sendReq(id, inc, f.src) // odmah traži sve (ili nastavlja postojeći)
                }
                FileFrame.CHUNK -> if (p.size >= 21) {
                    val b = ByteBuffer.wrap(p, 1, 4)
                    val id = p.copyOfRange(1, 17)
                    if (hex(id) in completed) continue
                    val idx = ByteBuffer.wrap(p, 17, 4).int
                    val inc = incoming[hex(id)] ?: continue
                    if (idx in 0 until inc.total && inc.buf.putIfAbsent(idx, p.copyOfRange(21, p.size)) == null) {
                        inc.lastActive = now
                        ev.add(Event.Progress(hex(id), inc.buf.size, inc.total))
                        if (inc.buf.size == inc.total) {
                            val body = (0 until inc.total).flatMap { inc.buf[it]!!.toList() }.toByteArray()
                            if (MessageDigest.getInstance("SHA-256").digest(body).contentEquals(inc.hash)) {
                                val plain = if (inc.compressed) inflate(body) else body
                                ev.add(Event.Received(inc.name, plain))
                                completed.add(hex(id))
                                sendDone(id, f.src)
                            }
                            incoming.remove(hex(id))
                        }
                    }
                }
                FileFrame.REQ -> if (p.size >= 19) {
                    val id = p.copyOfRange(1, 17)
                    val o = out[hex(id)] ?: continue
                    val n = ByteBuffer.wrap(p, 17, 2).short.toInt() and 0xFFFF
                    var off = 19
                    repeat(n.coerceAtMost(64)) {
                        if (off + 4 > p.size) return@repeat
                        val idx = ByteBuffer.wrap(p, off, 4).int; off += 4
                        if (idx in o.chunks.indices) {
                            val c = ByteBuffer.allocate(21 + o.chunks[idx].size)
                            c.put(FileFrame.CHUNK); c.put(id); c.putInt(idx); c.put(o.chunks[idx])
                            node.sendUnicast(o.dst, c.array(), Priority.BULK, ackReq = true, e2e = false)
                        }
                    }
                    o.manifestAt = now
                }
                FileFrame.DONE -> if (p.size == 17) out[p.copyOfRange(1, 17).let { hex(it) }]?.done = true
            }
        }
        // guranje: manifest svakih 50 tickova dok nije DONE; chunkovi u redoslijedu; rupe na neaktivnost 30
        out.values.filter { !it.done }.forEach { o ->
            val id = out.entries.first { it.value === o }.key
            if (now - o.manifestAt >= 50) sendManifest(id.hexToBytes(), o, now)
            var budget = 4
            while (o.idx < o.chunks.size && budget-- > 0) {
                val c = ByteBuffer.allocate(21 + o.chunks[o.idx].size)
                c.put(FileFrame.CHUNK); c.put(id.hexToBytes()); c.putInt(o.idx); c.put(o.chunks[o.idx]); o.idx++
                node.sendUnicast(o.dst, c.array(), Priority.BULK, ackReq = true, e2e = false)
            }
        }
        incoming.values.forEach { inc ->
            if (now - inc.lastActive >= 30 && inc.buf.size < inc.total) {
                val id = incoming.entries.first { it.value === inc }.key
                sendReq(id.hexToBytes(), inc, inc.dst); inc.lastActive = now
            }
        }
        return ev
    }

    private fun sendDone(id: ByteArray, dst: NodeId) {
        val d = ByteBuffer.allocate(17); d.put(FileFrame.DONE); d.put(id)
        node.sendUnicast(dst, d.array(), Priority.URGENT, ackReq = false, e2e = false)
    }

    private fun sendReq(id: ByteArray, inc: Incoming, dst: NodeId) {
        val missing = (0 until inc.total).filter { it !in inc.buf }.take(64)
        val b = ByteBuffer.allocate(19 + 4 * missing.size)
        b.put(FileFrame.REQ); b.put(id); b.putShort(missing.size.toShort())
        missing.forEach { b.putInt(it) }
        node.sendUnicast(dst, b.array(), Priority.URGENT, ackReq = true, e2e = false)
    }

    private fun inflate(b: ByteArray): ByteArray {
        val inf = Inflater(); inf.setInput(b)
        val out = mutableListOf<Byte>(); val tmp = ByteArray(4096)
        while (!inf.finished()) { val n = inf.inflate(tmp); if (n == 0) break; tmp.copyOf(n).forEach { out.add(it) } }
        inf.end(); return out.toByteArray()
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
