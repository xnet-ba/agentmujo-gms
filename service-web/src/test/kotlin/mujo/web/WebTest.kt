package mujo.web

import mujo.chat.ChatService
import mujo.chat.SosManager
import mujo.mesh.Identity
import mujo.mesh.MeshNode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.test.*

class WebTest {
    private fun node(name: String): MeshNode {
        val seed = java.security.MessageDigest.getInstance("SHA-256").digest(("mujo-id:" + name).toByteArray())
        val ident = Identity.deterministic(seed)
        return MeshNode(ident.nodeId(), identity = ident)
    }

    private fun get(url: String): Pair<Int, String> {
        val c = java.net.URI.create(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        val code = c.responseCode
        val body = try { c.inputStream.readAllBytes().toString(Charsets.UTF_8) } catch (_: Exception) { "" }
        c.disconnect(); return code to body
    }

    private fun post(url: String, form: Map<String, String>): Pair<Int, String> {
        val c = java.net.URI.create(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 3000; c.readTimeout = 3000
        val q = form.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }
        c.outputStream.use { it.write(q.toByteArray()) }
        val code = c.responseCode
        val body = try { c.inputStream.readAllBytes().toString(Charsets.UTF_8) } catch (_: Exception) { "" }
        c.disconnect(); return code to body
    }

    @Test fun statusEndpoint() {
        val n = node("A"); val w = WebService(n, ChatService(n), SosManager(n))
        try {
            val (code, body) = get(w.url + "/status")
            assertEquals(200, code)
            assertTrue(body.contains("\"neighbors\":0") && body.contains("\"battery\":100"))
        } finally { w.stop() }
    }

    @Test fun chatRoundtrip() {
        val n = node("A"); val cs = ChatService(n); val w = WebService(n, cs, SosManager(n))
        try {
            val (code, _) = post(w.url + "/chat", mapOf("group" to "tim", "text" to "zdravo svima"))
            assertEquals(200, code)
            val (_, body) = get(w.url + "/chat?group=tim")
            assertTrue(body.contains("tim|zdravo svima"), body)
        } finally { w.stop() }
    }

    @Test fun sosEndpoint() {
        val n = node("A"); val w = WebService(n, ChatService(n), SosManager(n))
        try {
            val (code, body) = post(w.url + "/sos", mapOf("text" to "pomoć", "lat" to "43.8", "lon" to "18.3"))
            assertEquals(200, code)
            assertEquals(32, body.length, "sosId hex 16B")
        } finally { w.stop() }
    }

    @Test fun loopbackOnly() {
        val n = node("A"); val w = WebService(n, ChatService(n), SosManager(n))
        try {
            assertTrue(w.url.startsWith("http://127.0.0.1:"), "samo loopback, nikad 0.0.0.0: ${w.url}")
        } finally { w.stop() }
    }
}
