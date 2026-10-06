package mujo.web

import com.sun.net.httpserver.HttpServer
import mujo.chat.ChatService
import mujo.chat.SosManager
import mujo.mesh.MeshNode
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Lokalni web/intranet (Faza 7a, pure-JDK, bez zavisnosti): dijagnostika i chat/SOS preko loopbacka.
 * Sluša ISKLJUČIVO 127.0.0.1 (nema izlaganja na mrežu); akcije su eksplicitne lokalne korisničke radnje.
 * [TESTIRANO-UNIT] (HttpURLConnection protiv živog servera).
 */
class WebService(
    val node: MeshNode,
    val chat: ChatService,
    val sos: SosManager,
    port: Int = 0, // 0 = efemeran
) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val url: String
    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
        server.createContext("/status") { ex ->
            val body = statusJson().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/chat") { ex ->
            if (ex.requestMethod == "POST") {
                val q = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val p = parseForm(q)
                chat.sendChat(p["group"] ?: "opšte", p["text"] ?: "")
                reply(ex, 200, "ok")
            } else {
                val group = ex.requestURI.query?.let { parseForm(it) }?.get("group")
                val msgs = chat.poll().filter { group == null || it.group == group }
                reply(ex, 200, msgs.joinToString("\n") { "${it.group}|${it.text}" })
            }
        }
        server.createContext("/sos") { ex ->
            if (ex.requestMethod != "POST") { reply(ex, 405, "post only"); return@createContext }
            val p = parseForm(ex.requestBody.readAllBytes().toString(Charsets.UTF_8))
            val id = sos.startSos(p["text"] ?: "SOS", p["lat"]?.toDoubleOrNull() ?: 0.0,
                p["lon"]?.toDoubleOrNull() ?: 0.0, System.currentTimeMillis())
            reply(ex, 200, id.joinToString("") { "%02x".format(it) })
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
    }

    fun stop() = server.stop(0)

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
        val b = body.toByteArray()
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun parseForm(q: String) = q.split("&").mapNotNull {
        val i = it.indexOf("=")
        if (i < 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()

    fun statusJson(): String {
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val sb = StringBuilder()
        sb.append("{\"node\":\"${hex(node.id.bytes)}\",")
        sb.append("\"neighbors\":${node.neighbors.size},")
        sb.append("\"battery\":${node.battery},\"charging\":${node.charging},")
        sb.append("\"roles\":[${node.roles().joinToString(",") { "\"$it\"" }}],")
        sb.append("\"coord\":\"${node.tracker.coord?.let { hex(it.bytes) } ?: "none"}\",")
        sb.append("\"delivered\":${node.delivered.size},\"sent\":${node.sent},\"forwarded\":${node.forwarded},")
        sb.append("\"lost\":${node.lost.size},")
        sb.append("\"drops\":{${node.drops.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}},")
        sb.append("\"routes\":${node.routes.size}}")
        return sb.toString()
    }
}
