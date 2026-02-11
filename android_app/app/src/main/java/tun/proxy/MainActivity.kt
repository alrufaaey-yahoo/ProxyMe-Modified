package tun.proxy

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import tun.proxy.service.Tun2SocksVpnService
import tun.proxy.service.Tun2SocksVpnService.Companion.ACTION_STOP_SERVICE
import tun.utils.Utils

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartStop: Button
    private lateinit var hostEditText: EditText

    private lateinit var utils: Utils
    private lateinit var intentVPNService: Intent
    private var chainProxy: ChainProxy? = null

    private val TAG = "MainActivity"
    private val VPN_REQUEST_CODE = 100
    private val REQUEST_NOTIFICATION_PERMISSION = 1231

    // ✅ البروكسي المحلي socks5
    private val PROXY = "http://127.0.0.1:2323"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        btnStartStop = findViewById(R.id.start)
        hostEditText = findViewById(R.id.host)

        utils = Utils(this)
        intentVPNService = Intent(this, Tun2SocksVpnService::class.java)

        chainProxy = ChainProxy()

        btnStartStop.setOnClickListener {
            if (Tun2SocksVpnService.isRunning()) {
                stopAll()
            } else {
                startAll()
            }
        }

        updateButton()
    }

    // =========================
    // START ALL
    // =========================

    private fun startAll() {
        Log.d(TAG, "Starting Proxy + VPN")

        try {
            chainProxy?.start()
        } catch (e: Exception) {
            Log.e(TAG, "ChainProxy start error: ${e.message}")
        }

        startVpn(PROXY)
    }

    // =========================
    // STOP ALL
    // =========================

    private fun stopAll() {
        Log.d(TAG, "Stopping VPN + Proxy")

        stopVpn()

        try {
            chainProxy?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "ChainProxy stop error: ${e.message}")
        }

        updateButton()
    }

    // =========================
    // BUTTON UI
    // =========================

    private fun updateButton() {
        val running = Tun2SocksVpnService.isRunning()

        if (running) {
            btnStartStop.text = "STOP VPN + PROXY"
            btnStartStop.setBackgroundColor(0xFFD32F2F.toInt())
        } else {
            btnStartStop.text = "START VPN + PROXY"
            btnStartStop.setBackgroundColor(0xFF388E3C.toInt())
        }

        hostEditText.isEnabled = !running
    }

    // =========================
    // VPN START
    // =========================

    private fun startVpn(proxy: String) {
        Log.d(TAG, "startVpn: $proxy")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }

        intentVPNService.putExtra("data", proxy)

        val intent = VpnService.prepare(this)

        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startService(intentVPNService)
            updateButton()
        }
    }

    // =========================
    // VPN STOP
    // =========================

    private fun stopVpn() {
        try {
            val intent = Intent(this, Tun2SocksVpnService::class.java)
            intent.action = ACTION_STOP_SERVICE
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "stopVpn error: ${e.message}")
        }
    }

    // =========================
    // RESULT
    // =========================

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startService(intentVPNService)
        }

        updateButton()
    }

    // =========================
    // PERMISSIONS
    // =========================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notification permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateButton()
    }
}
