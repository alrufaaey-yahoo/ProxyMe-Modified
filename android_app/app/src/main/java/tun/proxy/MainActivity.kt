package tun.proxy

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import tun.proxy.service.Tun2SocksVpnService
import tun.proxy.service.Tun2SocksVpnService.Companion.ACTION_STOP_SERVICE
import tun.utils.Utils

class MainActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private var start: Button? = null
    private var stop: Button? = null
    private var hostEditText: EditText? = null
    private var utils: Utils? = null
    private var service: Tun2SocksVpnService? = null
    private val TAG = "${BuildConfig.APPLICATION_ID}->${this.javaClass.simpleName}"
    private val VPN_REQUEST_CODE = 100
    private val REQUEST_NOTIFICATION_PERMISSION = 1231
    private var intentVPNService: Intent? = null
    private val PREF_USER_CONFIG: String = "pref_user_config"
    private val PREF_FORMATTED_CONFIG: String = "pref_formatted_config"
    
    // ChainProxy instance
    private var chainProxy: ChainProxy? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)
        hostEditText = findViewById(R.id.host)

        // Hardcoded proxy for startVpn
        val fixedProxy = "http://127.0.0.1:2323"

        start?.setOnClickListener { 
            startChainProxy()
            startVpn(this, fixedProxy) 
        }
        stop?.setOnClickListener { 
            stopVpn(this)
            stopChainProxy()
        }

        updateStatusView(st = true, stp = false)
        // loadHostPort() // Not needed anymore as we hide it

        utils = Utils(this)
        intentVPNService = Intent(this, Tun2SocksVpnService::class.java)
        
        chainProxy = ChainProxy()
    }

    private fun startChainProxy() {
        chainProxy?.start()
    }

    private fun stopChainProxy() {
        chainProxy?.stop()
    }

    private fun updateStatusView(st: Boolean, stp: Boolean) {
        start!!.isEnabled = st
        start!!.visibility = if (st) View.VISIBLE else View.GONE

        stop!!.isEnabled = stp
        stop!!.visibility = if (stp) View.VISIBLE else View.GONE
        hostEditText!!.isEnabled = !stp
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        // Disabled as we hide settings
        return false
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Hide settings menu
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item)
    }

    protected val versionName: String?
        get() {
            val packageManager = packageManager ?: return null

            return try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }


    val isRunning: Boolean
        get() = service != null && service!!.isRunning()


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "onActivityResult: User authorization succeeds, start VPN service")
                startService(intentVPNService)
            }
        }
    }

    private fun startVpn(context: Context, proxy: String) {
        Log.d(TAG, "startVpn: $proxy")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                this,
                POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }

        intentVPNService?.putExtra("data", proxy)
        val intent = VpnService.prepare(context)
        if (intent != null) {
            this.startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            context.startService(intentVPNService)
        }

        updateStatusView(st = false, stp = true)
    }

    private fun stopVpn(context: Context) {
        try {
            val intent = Intent(context, Tun2SocksVpnService::class.java)
            intent.setAction(ACTION_STOP_SERVICE)
            context.startService(intent)
            val result = context.stopService(intent)
            Log.d(TAG, "stopService: state:$result")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        updateStatusView(st = true, stp = false)
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
                Toast.makeText(
                    this,
                    "This app requires notification permission",
                    Toast.LENGTH_SHORT
                ).show()
                startNotificationSetting()
            }
        }
    }

    private fun startNotificationSetting() {
        val applicationInfo = applicationInfo
        try {
            val intent = Intent()
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.action = "android.settings.APP_NOTIFICATION_SETTINGS"
            intent.putExtra("app_package", applicationInfo.packageName)
            intent.putExtra("android.provider.extra.APP_PACKAGE", applicationInfo.packageName)
            intent.putExtra("app_uid", applicationInfo.uid)
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent()
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.action = "android.settings.APPLICATION_DETAILS_SETTINGS"
            intent.data = Uri.fromParts("package", applicationInfo.packageName, null)
            startActivity(intent)
        }
    }
}
