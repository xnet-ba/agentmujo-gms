package mujo.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import mujo.link.CostProfile
import mujo.link.LinkQuality
import mujo.link.LinkTransport
import mujo.link.PeerId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE transport iza LinkTransport (Faza 4-device, PC: samo kompilacija).
 * Status: [KOMPAJLIRA-SE] na API 34; ponašanje na uređajima [NETESTIRANO] —
 * advertising/scanning throttling, OEM ubijanje i MTU pregovori mjere se na hardveru.
 *
 * Dizajn v1: GATT server (periferija, TX notify + RX write) + GATT klijent (centrala).
 * Bez bonding-a (TODO: uparivanje Faza 3-UI). Service UUID fiksan (vidi dolje).
 */
@SuppressLint("MissingPermission") // svaki poziv je iza hasBlePermission(); lint-supresija je namjerna
class BleTransport(
    private val context: Context,
    private val shortId: ByteArray, // 4B: skraćeni node id za advertise paket (puni ide kroz GATT handshake, TODO)
) : LinkTransport {
    override val id = "ble"

    companion object {
        val SERVICE: UUID = UUID.fromString("8f1d2f2a-6b3c-4e5d-8a7b-9c0d1e2f3041")
        val TX_CHAR: UUID = UUID.fromString("8f1d2f2b-6b3c-4e5d-8a7b-9c0d1e2f3041") // server→central (notify)
        val RX_CHAR: UUID = UUID.fromString("8f1d2f2c-6b3c-4e5d-8a7b-9c0d1e2f3041") // central→server (write)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val knownRx = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    }

    private val manager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var receiver: ((PeerId, ByteArray) -> Unit)? = null
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private var server: BluetoothGattServer? = null
    private var discovering = false

    private fun hasBlePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun isAvailable(): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        return hasBlePermission()
    }

    override fun setReceiver(cb: (PeerId, ByteArray) -> Unit) { receiver = cb }

    // ---- discovery: BLE scan (centrala) + advertise (periferija) ----
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val dev = result.device ?: return
            val uuids = result.scanRecord?.serviceUuids.orEmpty()
            if (uuids.any { it.uuid == SERVICE }) {
                devices[dev.address] = dev
                scanListener?.invoke(PeerId(dev.address))
            }
        }
    }
    private var scanListener: ((PeerId) -> Unit)? = null

    private val advCb = object : AdvertiseCallback() {}

    override fun startDiscovery(onPeer: (PeerId) -> Unit) {
        if (!isAvailable()) return
        scanListener = onPeer
        discovering = true
        startAdv()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter?.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCb)
        startServer()
    }

    override fun stopDiscovery() {
        discovering = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCb) } catch (_: Exception) {}
    }

    private fun startAdv() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return // null: nema multi-advertise ili BT ugašen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE))
            .addServiceData(ParcelUuid(SERVICE), shortId.copyOf(4))
            .setIncludeDeviceName(false).build()
        try { adv.startAdvertising(settings, data, advCb) } catch (_: Exception) {}
    }

    // ---- GATT server (primam writeove, šaljem notify) ----
    private val txChar = BluetoothGattCharacteristic(TX_CHAR,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ)
    private val rxChar = BluetoothGattCharacteristic(RX_CHAR,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE)

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(dev: BluetoothDevice, reqId: Int, char: BluetoothGattCharacteristic,
            prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (char.uuid == RX_CHAR) receiver?.invoke(PeerId(dev.address), value.copyOf())
            server?.sendResponse(dev, reqId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
        override fun onDescriptorWriteRequest(dev: BluetoothDevice, reqId: Int, desc: android.bluetooth.BluetoothGattDescriptor,
            prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            server?.sendResponse(dev, reqId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
        override fun onConnectionStateChange(dev: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) devices[dev.address] = dev
        }
    }

    private fun startServer() {
        if (server != null || !hasBlePermission()) return
        val s = manager?.openGattServer(context, serverCb) ?: return
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        txChar.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(txChar); svc.addCharacteristic(rxChar)
        s.addService(svc); server = s
    }

    // ---- GATT klijent (centrala piše periferiji) ----
    override fun connect(peer: PeerId): LinkTransport.Link? {
        if (!isAvailable() || !discovering) return null
        val dev = devices[peer.address] ?: return null
        return GattLink(peer, dev)
    }

    override fun linkQuality(peer: PeerId): LinkQuality? {
        if (!devices.containsKey(peer.address)) return null
        return LinkQuality(lossEstimate = 0.0, mtuBytes = (mtus[peer.address] ?: 23) - 3) // 20 dok se MTU ne ispregovara
    }

    override fun costProfile() = CostProfile(
        energyPerKbJoule = 1.0, throughputKbps = 10.0, latencyMs = 100.0, mtuBytes = 20,
    ) // BLE baza; svi brojevi NEVALIDIRANI dok se ne izmjere na uređajima

    private inner class GattLink(override val peer: PeerId, dev: BluetoothDevice) : LinkTransport.Link {
        private var gatt: BluetoothGatt? = null
        private var tx: BluetoothGattCharacteristic? = null
        private var open = true

        private val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) open = false
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(SERVICE) ?: return
                tx = svc.getCharacteristic(TX_CHAR)
                val rx = svc.getCharacteristic(RX_CHAR) ?: return
                g.setCharacteristicNotification(tx, true)
                val cccd = tx?.getDescriptor(CCCD)
                if (cccd != null) {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                try { g.requestMtu(512) } catch (_: Exception) {}
                knownRx[peer.address] = rx
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtus[peer.address] = mtu
            }
        }

        init {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dev.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                dev.connectGatt(context, false, cb)
            }
        }

        override fun send(bytes: ByteArray): Boolean {
            val rx = knownRx[peer.address] ?: return false // servisi još neotkriveni
            @Suppress("DEPRECATION")
            rx.value = bytes
            @Suppress("DEPRECATION")
            return gatt?.writeCharacteristic(rx) == true
        }

        override fun close() {
            open = false
            try { gatt?.disconnect() } catch (_: Exception) {}
            try { gatt?.close() } catch (_: Exception) {}
            knownRx.remove(peer.address)
        }
    }
}
