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

    private val TAG = "Tun2SocksVPN"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var utils: Utils? = null
    private val stopSignal = CountDownLatch(1)

    companion object {
        const val ACTION_STOP_SERVICE = "${BuildConfig.APPLICATION_ID}.STOP_VPN_SERVICE"

        // ✅ tun2socks يحتاج SOCKS
        private const val PROXY = "http://127.0.0.1:2323"

        @Volatile
        private var RUNNING = false

        fun isRunning(): Boolean = RUNNING
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

        if (RUNNING) {
            Log.d(TAG, "VPN already running")
            return START_STICKY
        }

        startForeground(1, buildNotification())

        vpnThread = Thread {
            try {
                RUNNING = true
                utils?.setVpnStatus(true)
                startVpn()
            } catch (e: Exception) {
                Log.e(TAG, "VPN crash", e)
                RUNNING = false
            }
        }

        vpnThread!!.start()
        return START_STICKY
    }

    private fun startVpn() {

        Log.d(TAG, "Starting VPN → $PROXY")

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.10.0.1", 32)
            .addRoute("0.0.0.0", 0)

        // ✅ Facebook only
        try { builder.addAllowedApplication("com.facebook.katana") } catch (_: Exception) {}
        try { builder.addAllowedApplication("com.facebook.lite") } catch (_: Exception) {}

        // منع loop
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        vpnInterface = builder.establish()
            ?: throw IllegalStateException("VPN establish failed")

        val key = Key().apply {
            mark = 0
            mtu = 1500
            device = "fd://${vpnInterface!!.fd}"
            logLevel = "info"
            proxy = PROXY
            restAPI = ""
            tcpSendBufferSize = ""
            tcpReceiveBufferSize = ""
            tcpModerateReceiveBuffer = false
        }

        Engine.insert(key)
        Engine.start()

        Log.d(TAG, "Engine started")

        stopSignal.await()

        try { Engine.stop() } catch (_: Exception) {}
        vpnInterface?.close()
        vpnInterface = null
        RUNNING = false

        Log.d(TAG, "VPN stopped")
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN")
        try {
            stopSignal.countDown()
            vpnThread?.interrupt()
            Engine.stop()
        } catch (_: Exception) {}
        RUNNING = false
        utils?.setVpnStatus(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                "vpn",
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.lightColor = Color.BLUE
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "vpn")
            .setContentTitle("ProxyMe VPN")
            .setContentText("Facebook traffic only via local proxy")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
