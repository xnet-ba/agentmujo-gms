package mujo.net

import android.app.Service
import android.content.Intent
import android.os.IBinder
import mujo.chat.ChatService
import mujo.chat.SosManager
import mujo.mesh.Identity
import mujo.mesh.MeshNode

/**
 * Bound service iza AIDL (Faza 6a). [KOMPAJLIRA-SE], ponašanje [NETESTIRANO]:
 * MeshNode ovdje živi bez transporta (loopback) dok Faza 4-device ne spoji BleTransport.
 */
class MeshBinderService : Service() {
    private lateinit var node: MeshNode
    private lateinit var chat: ChatService
    private lateinit var sos: SosManager

    override fun onCreate() {
        super.onCreate()
        val ident = Identity.random()
        node = MeshNode(ident.nodeId(), identity = ident)
        chat = ChatService(node)
        sos = SosManager(node)
        // TODO(Faza 4-device): BleTransport + heartbeat petlja + perzistencija identiteta (Keystore).
    }

    override fun onBind(intent: Intent?): IBinder = object : IMujoMesh.Stub() {
        override fun getStatus(): String {
            fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
            return "{\"node\":\"${hex(node.id.bytes)}\",\"neighbors\":${node.neighbors.size}," +
                "\"battery\":${node.battery},\"delivered\":${node.delivered.size}}"
        }
        override fun sendChat(group: String, text: String) { chat.sendChat(group, text) }
        override fun startSos(text: String, lat: Double, lon: Double): String {
            val id = sos.startSos(text, lat, lon, System.currentTimeMillis())
            node.tick(System.currentTimeMillis())
            return id.joinToString("") { "%02x".format(it) }
        }
        override fun pollChats(group: String): String {
            return chat.poll().filter { it.group == group }.joinToString("\n") { "${it.group}|${it.text}" }
        }
    }
}
