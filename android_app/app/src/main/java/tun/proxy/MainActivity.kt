package tun.proxy

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
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

    private var btnStartStop: Button? = null
    private var hostEditText: EditText? = null
    private var utils: Utils? = null
    private var intentVPNService: Intent? = null
    private var chainProxy: ChainProxy? = null

    private val TAG = "MainActivity"
    private val VPN_REQUEST_CODE = 100
    private val REQUEST_NOTIFICATION_PERMISSION = 1231
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

        btnStartStop?.setOnClickListener {
            if (Tun2SocksVpnService.isRunning()) {
                stopAll()
            } else {
                startAll()
            }
        }

        updateButton()
    }

    private fun startAll() {
        Log.d(TAG, "Starting ChainProxy and VPN")
        chainProxy?.start()
        startVpn(PROXY)
        updateButton()
    }

    private fun stopAll() {
        Log.d(TAG, "Stopping VPN and ChainProxy")
        stopVpn()
        chainProxy?.stop()
        updateButton()
    }

    private fun updateButton() {
        val running = Tun2SocksVpnService.isRunning()
        btnStartStop?.text = if (running) "Stop VPN+Proxy" else "Start VPN+Proxy"
        hostEditText?.isEnabled = !running
    }

    private fun startVpn(proxy: String) {
        Log.d(TAG, "startVpn: $proxy")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }

        intentVPNService?.putExtra("data", proxy)

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startService(intentVPNService)
        }
    }

    private fun stopVpn() {
        try {
            val intent = Intent(this, Tun2SocksVpnService::class.java)
            intent.action = ACTION_STOP_SERVICE
            startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startService(intentVPNService)
        }
        updateButton()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
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
