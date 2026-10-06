package mujo.link

/**
 * Sloj 1 apstrakcija (Faza 4a, PC-testabilno). Android implementacije (BLE/Aware/Direct/LAN)
 * dolaze s uređajima; ovdje samo ugovor + loopback za testove. [TESTIRANO-UNIT]
 */
data class PeerId(val address: String)

/** Procjena linka prema susjedu; popunjava LinkManager (Faza 5), ovdje tip + loopback heuristika. */
data class LinkQuality(
    val rssiDbm: Int? = null, // BLE/Wi-Fi; null = transport ne mjeri
    val lossEstimate: Double = 0.0, // 0..1
    val mtuBytes: Int = 512,
)

/** Troškovni profil transporta: bira ga LinkManager po tipu saobraćaja (Faza 5). */
data class CostProfile(
    val energyPerKbJoule: Double, // relativna skala; 1.0 = BLE baza (NEVALIDIRANO, mjeri se Faza 4-device)
    val throughputKbps: Double,
    val latencyMs: Double,
    val mtuBytes: Int,
)

interface LinkTransport {
    val id: String // "ble" | "aware" | "direct" | "lan" | "loopback"
    fun isAvailable(): Boolean
    fun startDiscovery(onPeer: (PeerId) -> Unit)
    fun stopDiscovery()
    fun connect(peer: PeerId): Link?
    fun setReceiver(cb: (peer: PeerId, bytes: ByteArray) -> Unit)
    fun linkQuality(peer: PeerId): LinkQuality?
    fun costProfile(): CostProfile

    interface Link {
        val peer: PeerId
        fun send(bytes: ByteArray): Boolean
        fun close()
    }
}
