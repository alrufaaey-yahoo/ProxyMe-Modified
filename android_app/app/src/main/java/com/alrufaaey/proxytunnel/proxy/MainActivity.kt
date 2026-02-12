package com.alrufaaey.proxytunnel.proxy

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.alrufaaey.proxytunnel.R
import com.alrufaaey.proxytunnel.proxy.service.Tun2SocksVpnService
import com.alrufaaey.proxytunnel.proxy.service.Tun2SocksVpnService.Companion.ACTION_STOP_SERVICE
import com.alrufaaey.proxytunnel.utils.Utils

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartStop: Button
    private lateinit var userEditText: EditText
    private lateinit var passEditText: EditText

    private lateinit var utils: Utils
    private lateinit var intentVPNService: Intent
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
        userEditText = findViewById(R.id.proxy_username)
        passEditText = findViewById(R.id.proxy_password)

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

    private fun startAll() {
        Log.d(TAG, "Starting Proxy + VPN")

        try {
            val username = userEditText.text.toString()
            val password = passEditText.text.toString()
            chainProxy?.setCredentials(username, password)
            chainProxy?.start()
        } catch (e: Exception) {
            Log.e(TAG, "ChainProxy start error: ${e.message}")
        }

        startVpn(PROXY)
    }

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

    private fun updateButton() {
        val running = Tun2SocksVpnService.isRunning()

        if (running) {
            btnStartStop.text = "إيقاف"
            btnStartStop.setBackgroundResource(R.drawable.button_red)
        } else {
            btnStartStop.text = "تشغيل"
            btnStartStop.setBackgroundResource(R.drawable.button_blue)
        }

        userEditText.isEnabled = !running
        passEditText.isEnabled = !running
    }

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

    private fun stopVpn() {
        try {
            val intent = Intent(this, Tun2SocksVpnService::class.java)
            intent.action = ACTION_STOP_SERVICE
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "stopVpn error: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startService(intentVPNService)
        }

        updateButton()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("عن المطور")
            .setMessage("تم التطوير بواسطة الرفاعي\nhttps://t.me/alrufaaey")
            .setPositiveButton("إغلاق", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateButton()
    }
}
