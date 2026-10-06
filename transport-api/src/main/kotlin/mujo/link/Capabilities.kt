package mujo.link

/**
 * Capability Probe MODEL (Faza 4a, čisti Kotlin). Vrijednosti na Androidu čita
 * `AndroidCapabilityProbe` (Faza 4-device, NETESTIRANO); ovdje tipovi + Fake za testove.
 */
data class CapabilityReport(
    val transports: Set<String>, // npr. {"ble","lan"} — šta je prisutno
    val bleAdvertising: Boolean, // BLE advertise podržan (često laže na papiru → mjeriti!)
    val wifiAware: Boolean,
    val wifiDirect: Boolean,
    val batteryPct: Int,
    val charging: Boolean,
    val gps: Boolean,
    val storageFreeMb: Long,
    val cpuClass: Int, // 1..3 (jezgra×klasa, grubo)
    val ramMb: Long,
    val microphone: Boolean,
    val internet: Boolean, // za Gateway mode (default OFF, uz dozvolu)
    val androidVersion: Int, // API level; 0 = ne-Android (sim/PC)
    val oemBackground: String, // "unknown" dok se ne izmjeri na uređaju
)

interface CapabilityProbe {
    fun probe(): CapabilityReport
}

/** Deterministički Fake za testove i sim. */
class FakeProbe(private val report: CapabilityReport) : CapabilityProbe {
    override fun probe() = report
    companion object {
        fun flagship() = FakeProbe(CapabilityReport(
            transports = setOf("ble", "aware", "direct", "lan"), bleAdvertising = true,
            wifiAware = true, wifiDirect = true, batteryPct = 85, charging = false,
            gps = true, storageFreeMb = 8192, cpuClass = 3, ramMb = 8192,
            microphone = true, internet = false, androidVersion = 34, oemBackground = "unknown"))
        fun oldPhone() = FakeProbe(CapabilityReport(
            transports = setOf("ble", "lan"), bleAdvertising = false,
            wifiAware = false, wifiDirect = true, batteryPct = 40, charging = false,
            gps = false, storageFreeMb = 512, cpuClass = 1, ramMb = 2048,
            microphone = true, internet = false, androidVersion = 26, oemBackground = "unknown"))
    }
}
