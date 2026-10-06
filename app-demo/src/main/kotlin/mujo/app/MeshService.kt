package mujo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground servis mesh stacka (Faza 4-device). [KOMPAJLIRA-SE], ponašanje [NETESTIRANO]:
 * preživljavanje na OEM-ima, Doze, BLE-scan throttling mjere se na uređajima.
 */
class MeshService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null // AIDL binding dolazi u Fazi 6

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        // TODO(Faza 4-device): ovdje se bude MeshNode + BleTransport + heartbeat petlja.
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mesh", "MujoMesh", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, "mesh")
            .setContentTitle("MujoMesh aktivan")
            .setContentText("Offline mesh radi u pozadini")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(1, notif)
        }
    }
}
