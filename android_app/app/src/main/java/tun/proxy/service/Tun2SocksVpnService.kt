package tun.proxy.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import engine.Engine
import engine.Key
import tun.proxy.BuildConfig
import tun.proxy.MainActivity
import tun.proxy.R
import tun.utils.Utils
import java.util.concurrent.CountDownLatch

class Tun2SocksVpnService : VpnService() {

    private val TAG = "VPN"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var utils: Utils? = null
    private val stopSignal = CountDownLatch(1)

    companion object {
        const val ACTION_STOP_SERVICE = "${BuildConfig.APPLICATION_ID}.STOP_VPN_SERVICE"
        private const val PROXY = "http://127.0.0.1:2323"
    }

    override fun onCreate() {
        super.onCreate()
        utils = Utils(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == ACTION_STOP_SERVICE) {
            stopVpn()
            return START_NOT_STICKY
        }

        startForeground(1, buildNotification())

        vpnThread = Thread {
            try {
                utils?.setVpnStatus(true)
                startVpn()
            } catch (e: Exception) {
                Log.e(TAG, "VPN crash", e)
            }
        }

        vpnThread!!.start()
        return START_STICKY
    }

    private fun startVpn() {

        val builder = Builder()
            .setSession("ProxyMe")
            .setMtu(1500)
            .addAddress("10.10.0.1", 32)
            .addRoute("0.0.0.0", 0)

        // ✅ Facebook only (both variants)
        try { builder.addAllowedApplication("com.facebook.katana") } catch (_: Exception) {}
        try { builder.addAllowedApplication("com.facebook.lite") } catch (_: Exception) {}

        // prevent loop
        builder.addDisallowedApplication(packageName)

        vpnInterface = builder.establish()
            ?: throw IllegalStateException("VPN establish failed")

        val key = Key().apply {
            mark = 0
            mtu = 1500
            device = "fd://${vpnInterface!!.fd}"
            logLevel = "info"
            proxy = PROXY   // ✅ SOCKS — مهم
        }

        Engine.insert(key)
        Engine.start()

        Log.d(TAG, "Engine started → $PROXY")

        stopSignal.await()

        Engine.stop()
        vpnInterface?.close()
    }

    private fun stopVpn() {
        try {
            stopSignal.countDown()
            vpnThread?.interrupt()
            Engine.stop()
            utils?.setVpnStatus(false)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                "vpn",
                "VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.lightColor = Color.BLUE
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "vpn")
            .setContentTitle("ProxyMe VPN")
            .setContentText("Facebook only via local proxy")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .build()
    }
}
