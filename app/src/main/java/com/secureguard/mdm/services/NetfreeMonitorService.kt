package com.secureguard.mdm.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.secureguard.mdm.R
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.utils.FileLogger
import com.secureguard.mdm.utils.JobSchedulerHelper
import com.secureguard.mdm.utils.NetfreeChecker
import com.secureguard.mdm.utils.NetworkWatcher
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "NetfreeMonitorService"
private const val NOTIFICATION_CHANNEL_ID = "NetfreeMonitorChannel"
private const val NOTIFICATION_ID = 1001

class NetfreeMonitorService : Service(), NetworkWatcher.NetworkStateListener {

    private lateinit var networkWatcher: NetworkWatcher
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponentName: ComponentName
    private lateinit var connectivityManager: ConnectivityManager

    private val approvedNetworks = ConcurrentHashMap<Network, Int>()

    companion object {
        const val ACTION_START_MONITORING = "ACTION_START_MONITORING"
        const val ACTION_STOP_MONITORING = "ACTION_STOP_MONITORING"
        private const val PREFS_NAME = "NetfreeServiceState"
        private const val KEY_IS_SERVICE_ACTIVE = "is_service_active"

        fun setServiceActive(context: Context, isActive: Boolean) {
            FileLogger.log(TAG, "Setting service active state to: $isActive")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_IS_SERVICE_ACTIVE, isActive)
            }
        }

        fun isServiceActive(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_IS_SERVICE_ACTIVE, false)
        }
    }

    private fun showToast(message: String) {
        mainScope.launch { Toast.makeText(this@NetfreeMonitorService, message, Toast.LENGTH_LONG).show() }
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.log(TAG, "Service onCreate.")
        networkWatcher = NetworkWatcher(this, this)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        adminComponentName = SecureGuardDeviceAdminReceiver.getComponentName(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                FileLogger.log(TAG, "Received START_MONITORING action.")
                JobSchedulerHelper.scheduleWatchdog(this)
                startForeground(NOTIFICATION_ID, createNotification())
                enablePersistentFirewall()
                approvedNetworks.clear()
                applyCurrentNetworkPolicy()
                networkWatcher.startWatching()
                setServiceActive(this, true)
            }
            ACTION_STOP_MONITORING -> {
                FileLogger.log(TAG, "Received STOP_MONITORING action.")
                JobSchedulerHelper.cancelWatchdog(this)
                disablePersistentFirewall()
                networkWatcher.stopWatching()
                setServiceActive(this, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onNetworkAvailable(network: Network) {
        FileLogger.log(TAG, "Network available: $network. Performing check.")
        performNetfreeCheck(network)
    }

    override fun onNetworkLost(network: Network) {
        FileLogger.log(TAG, "Network lost: $network. Updating policy.")
        if (approvedNetworks.containsKey(network)) {
            approvedNetworks.remove(network)
            applyCurrentNetworkPolicy()
        }
    }

    private fun performNetfreeCheck(network: Network) {
        showToast(getString(R.string.toast_netfree_check_started))
        serviceScope.launch {
            try {
                val isFiltered = NetfreeChecker.isNetfreeFiltered(network)
                if (isFiltered) {
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    val transportType = when {
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> NetworkCapabilities.TRANSPORT_WIFI
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> NetworkCapabilities.TRANSPORT_CELLULAR
                        else -> -1
                    }
                    if (transportType != -1) {
                        FileLogger.log(TAG, "Network $network approved (Type: $transportType).")
                        approvedNetworks[network] = transportType
                    }
                } else {
                    FileLogger.log(TAG, "Network $network is NOT approved.")
                    approvedNetworks.remove(network)
                }
            } catch (e: Exception) {
                FileLogger.log(TAG, "Exception during Netfree check for network $network: ${e.message}")
                approvedNetworks.remove(network)
            } finally {
                applyCurrentNetworkPolicy()
            }
        }
    }

    private fun applyCurrentNetworkPolicy() {
        val preferredNetwork = determinePreferredNetwork()
        if (preferredNetwork != null) {
            showToast(getString(R.string.toast_netfree_check_success))
        } else {
            showToast(getString(R.string.toast_netfree_check_fail_blocking))
        }

        val vpnIntent = Intent(this, BlockerVpnService::class.java).apply {
            action = BlockerVpnService.ACTION_UPDATE_POLICY
            putExtra(BlockerVpnService.EXTRA_PREFERRED_NETWORK, preferredNetwork)
        }
        ContextCompat.startForegroundService(this, vpnIntent)
    }

    private fun determinePreferredNetwork(): Network? {
        if (approvedNetworks.isEmpty()) return null

        val wifi = approvedNetworks.entries.find { it.value == NetworkCapabilities.TRANSPORT_WIFI }
        if (wifi != null) {
            FileLogger.log(TAG, "Determined preferred network: Wi-Fi ${wifi.key}")
            return wifi.key
        }

        val cellular = approvedNetworks.entries.find { it.value == NetworkCapabilities.TRANSPORT_CELLULAR }
        if (cellular != null) {
            FileLogger.log(TAG, "Determined preferred network: Cellular ${cellular.key}")
            return cellular.key
        }
        return null
    }

    private fun enablePersistentFirewall() {
        FileLogger.log(TAG, "Enabling persistent firewall (Always-On VPN).")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                dpm.setAlwaysOnVpnPackage(adminComponentName, packageName, false)
            } catch (e: Exception) {
                FileLogger.log(TAG, "ERROR enabling persistent firewall: ${e.message}")
            }
        }
    }

    private fun disablePersistentFirewall() {
        FileLogger.log(TAG, "Disabling persistent firewall.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                dpm.setAlwaysOnVpnPackage(adminComponentName, null, false)
            } catch (e: Exception) {
                FileLogger.log(TAG, "ERROR disabling persistent firewall: ${e.message}")
            }
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Netfree Monitor Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "התראה קבועה המציינת שסינון נטפרי מנוטר ברקע."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("A Bloq")
            .setContentText("מצב סינון נטפרי מנוטר ברקע.")
            .setSmallIcon(R.drawable.ic_netguard_shield)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mainScope.cancel()
        if (::networkWatcher.isInitialized) {
            networkWatcher.stopWatching()
        }
        FileLogger.log(TAG, "Service onDestroy.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}