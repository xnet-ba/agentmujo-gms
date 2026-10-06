package mujo.files

import mujo.mesh.Identity
import mujo.mesh.MeshNode
import mujo.mesh.Tx
import kotlin.test.*

class FilesTest {
    private fun node(name: String): MeshNode {
        val seed = java.security.MessageDigest.getInstance("SHA-256").digest(("mujo-id:" + name).toByteArray())
        val ident = Identity.deterministic(seed)
        return MeshNode(ident.nodeId(), identity = ident)
    }
    private fun link(a: MeshNode, b: MeshNode) { a.neighbors.add(b.id); b.neighbors.add(a.id) }

    /** Pumpa s programibilnim gubicima: dropWhen odlučuje koji Tx umire (za resume test). */
    private fun pump(a: MeshNode, b: MeshNode, fa: FileTransfer, fb: FileTransfer, now: Long, dropWhen: (ByteArray) -> Boolean = { false }) {
        for (t in a.tick(now)) if (!dropWhen(t.bytes)) b.receive(t.bytes, a.id, now)
        for (t in b.tick(now)) if (!dropWhen(t.bytes)) a.receive(t.bytes, b.id, now)
        fa.poll(now); fb.poll(now)
    }

    @Test fun fileRoundtrip() {
        val a = node("A"); val b = node("B"); link(a, b)
        val fa = FileTransfer(a, chunkSize = 256); val fb = FileTransfer(b, chunkSize = 256)
        val data = ByteArray(5000) { (it * 31 % 251).toByte() }
        val rec = mutableListOf<FileTransfer.Event>()
        fa.offerFile(b.id, "foto.bin", data, compress = false, now = 0)
        repeat(400) { val t = it.toLong() + 1
            for (t2 in a.tick(t)) b.receive(t2.bytes, a.id, t)
            for (t2 in b.tick(t)) a.receive(t2.bytes, b.id, t)
            rec += fa.poll(t); rec += fb.poll(t)
        }
        val done = rec.filterIsInstance<FileTransfer.Event.Received>()
        assertEquals(1, done.size); assertEquals("foto.bin", done[0].name)
        assertTrue(done[0].bytes.contentEquals(data), "bajt-identičan prijem")
    }

    @Test fun resumeAfterLoss() {
        val a = node("A"); val b = node("B"); link(a, b)
        val fa = FileTransfer(a, chunkSize = 128); val fb = FileTransfer(b, chunkSize = 128)
        val data = ByteArray(2000) { it.toByte() }
        val rec = mutableListOf<FileTransfer.Event>()
        fa.offerFile(b.id, "doc.bin", data, compress = false, now = 0)
        var n = 0
        repeat(500) { val t = it.toLong() + 1
            // prvih 100 tickova gubi ~50% chunkova (samo CHUNK tip, ne manifest/req)
            val drop: (ByteArray) -> Boolean = { raw ->
                t < 100 && raw.size > 200 && (n++ % 2 == 0)
            }
            for (t2 in a.tick(t)) { if (!drop(t2.bytes)) b.receive(t2.bytes, a.id, t) }
            for (t2 in b.tick(t)) { if (!drop(t2.bytes)) a.receive(t2.bytes, b.id, t) }
            rec += fa.poll(t); rec += fb.poll(t)
        }
        val done = rec.filterIsInstance<FileTransfer.Event.Received>()
        assertEquals(1, done.size, "resume kroz FILE_REQ mora kompletirati")
        assertTrue(done[0].bytes.contentEquals(data))
    }

    @Test fun compression() {
        val a = node("A"); val b = node("B"); link(a, b)
        val fa = FileTransfer(a, chunkSize = 512); val fb = FileTransfer(b, chunkSize = 512)
        val data = ByteArray(4000) { 'A'.code.toByte() } // visoko kompresibilno
        val rec = mutableListOf<FileTransfer.Event>()
        val fid = fa.offerFile(b.id, "a.txt", data, compress = true, now = 0)
        assertEquals(1, fa.outChunks().values.first(), "kompresovano staje u 1 chunk (nekompresovano bi bilo 8)")
        repeat(300) { val t = it.toLong() + 1
            for (t2 in a.tick(t)) b.receive(t2.bytes, a.id, t)
            for (t2 in b.tick(t)) a.receive(t2.bytes, b.id, t)
            rec += fa.poll(t); rec += fb.poll(t)
        }
        val done = rec.filterIsInstance<FileTransfer.Event.Received>()
        assertEquals(1, done.size)
        assertTrue(done[0].bytes.contentEquals(data))
        assertNotNull(fid)
    }
}
