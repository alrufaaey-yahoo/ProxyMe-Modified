package tun.proxy.service

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
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

        // tun2socks يتطلب بروكسي SOCKS5
        private const val PROXY = "http://127.0.0.1:2323"

        @Volatile
        private var RUNNING = false

        fun isRunning(): Boolean = RUNNING
    }

    override fun onCreate() {
        super.onCreate()
        utils = Utils(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // لو تم استدعاء STOP
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopVpn()
            return START_NOT_STICKY
        }

        // لو الـ VPN يعمل بالفعل
        if (RUNNING) {
            Log.d(TAG, "VPN already running")
            return START_STICKY
        }

        startForeground(1, buildNotification())

        // بدء الـ VPN في Thread منفصل
        vpnThread = Thread {
            try {
                RUNNING = true
                utils?.setVpnStatus(true)
                startVpn()
            } catch (e: Exception) {
                Log.e(TAG, "VPN encountered error", e)
                RUNNING = false
            }
        }
        vpnThread!!.start()

        return START_STICKY
    }

    private fun startVpn() {

        Log.d(TAG, "Starting VPN with proxy $PROXY")

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.10.10.1", 32)
            .addRoute("0.0.0.0", 0)

        // تمكين دعم التطبيقات المحددة فقط
        try { builder.addAllowedApplication("com.facebook.katana") } catch (_: Exception) {}
        try { builder.addAllowedApplication("com.facebook.lite") } catch (_: Exception) {}

        // منع عمل الخدمة على نفسها لتجنب Loop
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            RUNNING = false
            return
        }

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

        // الانتظار حتى يتم إيقاف الخدمة
        stopSignal.await()

        try { Engine.stop() } catch (_: Exception) {}
        vpnInterface?.close()
        vpnInterface = null
        RUNNING = false
        Log.d(TAG, "VPN stopped")
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN")
        stopSignal.countDown()
        try { vpnThread?.interrupt() } catch (_: Exception) {}
        try { Engine.stop() } catch (_: Exception) {}
        RUNNING = false
        utils?.setVpnStatus(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vpn",
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.lightColor = Color.BLUE
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
            .setContentText("Running with proxy $PROXY")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
