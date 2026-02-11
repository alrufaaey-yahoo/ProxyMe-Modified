package tun.proxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import tun.proxy.MyApplication
import tun.proxy.R
import tun.utils.Utils
import java.util.concurrent.CountDownLatch

class Tun2SocksVpnService : VpnService() {
    private val TAG = "${BuildConfig.APPLICATION_ID}->${this.javaClass.simpleName}"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var proxyData: String? = null
    private var utils: Utils? = null
    private val serviceName: String = "ProxyMe"
    private val channelId = "${BuildConfig.APPLICATION_ID}_vpn_channel"
    private val channelName = serviceName
    private val stopSignal = CountDownLatch(1)


    companion object {
        const val ACTION_STOP_SERVICE = "${BuildConfig.APPLICATION_ID}.STOP_VPN_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        utils = Utils(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = "$channelName VPN Service Channel"
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopVpn()
            utils?.setVpnStatus(false)
            utils?.setProxyName("")
            return START_NOT_STICKY
        }
        
        // Force proxy to local ChainProxy address
        proxyData = "http://127.0.0.1:2323"
        
        Log.d(TAG, "onStartCommand: Starting VPN with local proxy $proxyData")
        vpnThread = object : Thread() {
            override fun run() {
                try {
                    utils?.setVpnStatus(true)
                    startVpn(proxyData!!)
                } catch (e: Exception) {
                    Log.e(TAG, "vpnThread: fail", e)
                }
            }
        }
        vpnThread?.start()

        val notificationIntent = Intent(this, MainActivity::class.java)
            .putExtra(channelId, true)
            .putExtra("proxyData", serviceName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "${BuildConfig.APPLICATION_ID}_vpn_channel")
            .setContentTitle("${applicationInfo.loadLabel(packageManager)}")
            .setContentText("VPN active for Facebook Lite via $proxyData")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)

        return START_NOT_STICKY
    }

    private fun startVpn(proxyDetails: String) {
        Log.d(TAG, "startVpn: $proxyDetails")
        proxyData = proxyDetails
        val builder = Builder()
            .addAddress("10.1.10.1", 32)
            .addAddress( "fd00:1:fd00:1:fd00:1:fd00:1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("0:0:0:0:0:0:0:0", 0)
            .setMtu(1500)
            .setSession(getString(R.string.app_name))

        // IMPORTANT: Route ONLY Facebook Lite through the VPN
        try {
            builder.addAllowedApplication("com.facebook.lite")
            Log.d(TAG, "Successfully restricted VPN to com.facebook.lite")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding allowed application: ${e.message}")
        }

        // Exclude this app to prevent loops
        builder.addDisallowedApplication(packageName)

        try {
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "vpnInterface: create establish error ")
                return
            }

            val key = Key()
            key.mark = 0
            key.mtu = 1500
            key.device = "fd://" + vpnInterface!!.fd
            key.setInterface("")
            key.logLevel = "debug"
            
            // Set the proxy to the local ChainProxy
            key.proxy = proxyDetails 
            
            key.restAPI = ""
            key.tcpSendBufferSize = ""
            key.tcpReceiveBufferSize = ""
            key.tcpModerateReceiveBuffer = false
            
            Engine.insert(key)
            Engine.start()
            
            Log.d(TAG, "Engine started with proxy: ${key.proxy}")
            utils?.setProxyName(serviceName)
            stopSignal.await()
        } catch (e: Exception) {
            Log.e(TAG, "startEngine: error ${e.message}")
        } finally {
            if (vpnInterface != null) {
                try { Engine.stop() } catch (e: Exception) {}
                vpnInterface?.close()
                vpnInterface = null
            }
            Log.d(TAG, "stopEngine: success!")
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "stopVpn: vpnInterface $vpnInterface")
        try {
            stopSignal.countDown()
            if (isRunning()) {
                vpnThread?.interrupt()
            }
            try { Engine.stop() } catch (e: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "stopVpn: ${e.message}")
        }
    }

    fun isRunning(): Boolean {
        return vpnThread != null && vpnThread?.isAlive == true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        Log.d(TAG, "onDestroy: ")
    }
}
