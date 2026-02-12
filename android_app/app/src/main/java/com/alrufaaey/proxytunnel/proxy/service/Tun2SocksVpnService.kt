package com.alrufaaey.proxytunnel.proxy.service

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
import com.alrufaaey.proxytunnel.proxy.BuildConfig
import com.alrufaaey.proxytunnel.proxy.MainActivity
import com.alrufaaey.proxytunnel.proxy.R
import com.alrufaaey.proxytunnel.utils.Utils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class Tun2SocksVpnService : VpnService() {

    private val TAG = "Tun2SocksVPN"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var utils: Utils? = null
    private val stopSignal = CountDownLatch(1)
    private val isStopping = AtomicBoolean(false)

    companion object {
        const val ACTION_STOP_SERVICE = "${BuildConfig.APPLICATION_ID}.STOP_VPN_SERVICE"
        private const val PROXY = "http://127.0.0.1:2323"
        
        @Volatile
        private var RUNNING = false
        
        fun isRunning(): Boolean = RUNNING
        
        fun setRunning(running: Boolean) {
            RUNNING = running
        }
    }

    override fun onCreate() {
        super.onCreate()
        utils = Utils(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.d(TAG, "Stop command received")
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
                isStopping.set(false)
                utils?.setVpnStatus(true)
                startVpn()
            } catch (e: Exception) {
                Log.e(TAG, "VPN error", e)
                RUNNING = false
            } finally {
                RUNNING = false
                utils?.setVpnStatus(false)
            }
        }
        
        vpnThread!!.start()
        
        return START_STICKY
    }

    private fun startVpn() {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.10.10.1", 32)
            .addRoute("0.0.0.0", 0)

        // السماح فقط لتطبيقات Facebook
        try { builder.addAllowedApplication("com.facebook.katana") } catch (_: Exception) {}
        try { builder.addAllowedApplication("com.facebook.lite") } catch (_: Exception) {}
        
        // منع الخدمة من العمل على التطبيق نفسه لتفادي loop
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
        
        Log.d(TAG, "Engine started with proxy: $PROXY")

        // انتظار إشارة الإيقاف
        try {
            stopSignal.await()
        } catch (e: InterruptedException) {
            Log.d(TAG, "VPN thread interrupted")
        }

        // إيقاف المحرك
        try {
            Engine.stop()
            Log.d(TAG, "Engine stopped")
        } catch (_: Exception) {}

        // إغلاق واجهة VPN
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}

        RUNNING = false
        Log.d(TAG, "VPN stopped completely")
    }

    private fun stopVpn() {
        Log.d(TAG, "stopVpn called")
        
        if (isStopping.getAndSet(true)) {
            Log.d(TAG, "Already stopping")
            return
        }

        try {
            // إرسال إشارة الإيقاف
            stopSignal.countDown()
            
            // مقاطعة الموضوع إذا كان يعمل
            vpnThread?.interrupt()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vpn thread: ${e.message}")
        }

        try {
            // إيقاف المحرك
            Engine.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping engine: ${e.message}")
        }

        RUNNING = false
        isStopping.set(false)
        
        utils?.setVpnStatus(false)
        
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground: ${e.message}")
        }
        
        try {
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service: ${e.message}")
        }
        
        Log.d(TAG, "Stop VPN completed")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "vpn")
            .setContentTitle("ProxyMe VPN")
            .setContentText("VPN active for Facebook via $PROXY")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
