package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.VpnRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class XrayVpnService : VpnService() {

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    companion object {
        const val ACTION_CONNECT = "com.example.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.DISCONNECT"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_SERVER_HOST = "extra_server_host"
        const val EXTRA_SPLIT_TUNNEL_ENABLED = "extra_split_tunnel_enabled"
        const val EXTRA_SPLIT_MODE = "extra_split_mode" // "PROXY" or "BYPASS"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "xray_vpn_service_channel"

        private val _vpnState = MutableStateFlow(State.DISCONNECTED)
        val vpnState: StateFlow<State> = _vpnState.asStateFlow()

        private val _rxBytes = MutableStateFlow(0L)
        val rxBytes: StateFlow<Long> = _rxBytes.asStateFlow()

        private val _txBytes = MutableStateFlow(0L)
        val txBytes: StateFlow<Long> = _txBytes.asStateFlow()

        private val _activeServerName = MutableStateFlow("")
        val activeServerName: StateFlow<String> = _activeServerName.asStateFlow()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var connectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: ""
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Xray Server"
                val serverHost = intent.getStringExtra(EXTRA_SERVER_HOST) ?: "127.0.0.1"
                val splitEnabled = intent.getBooleanExtra(EXTRA_SPLIT_TUNNEL_ENABLED, false)
                val splitMode = intent.getStringExtra(EXTRA_SPLIT_MODE) ?: "PROXY"

                _activeServerName.value = serverName
                startForeground(NOTIFICATION_ID, buildNotification("Connecting to $serverName..."))
                startVpnTunnel(serverName, serverHost, splitEnabled, splitMode)
            }
            ACTION_DISCONNECT -> {
                stopVpnTunnel()
            }
        }
        return START_STICKY
    }

    private fun startVpnTunnel(serverName: String, serverHost: String, splitEnabled: Boolean, splitMode: String) {
        connectionJob?.cancel()
        _vpnState.value = State.CONNECTING

        connectionJob = serviceScope.launch {
            try {
                val builder = Builder()
                    .setSession(serverName)
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)

                // Apply Per-App Split Tunneling rules
                if (splitEnabled) {
                    val repository = VpnRepository(applicationContext)
                    val proxiedPackages = repository.getProxiedAppPackages()

                    if (proxiedPackages.isNotEmpty()) {
                        if (splitMode == "PROXY") {
                            // Only selected apps route through VPN
                            proxiedPackages.forEach { pkg ->
                                try {
                                    builder.addAllowedApplication(pkg)
                                    Log.d("XrayVpnService", "Allowed app for VPN proxy: $pkg")
                                } catch (e: Exception) {
                                    Log.e("XrayVpnService", "Could not add allowed app $pkg: ${e.message}")
                                }
                            }
                        } else {
                            // Selected apps bypass VPN
                            proxiedPackages.forEach { pkg ->
                                try {
                                    builder.addDisallowedApplication(pkg)
                                    Log.d("XrayVpnService", "Disallowed app bypassing VPN: $pkg")
                                } catch (e: Exception) {
                                    Log.e("XrayVpnService", "Could not add disallowed app $pkg: ${e.message}")
                                }
                            }
                        }
                    }
                }

                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    _vpnState.value = State.DISCONNECTED
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    return@launch
                }

                _vpnState.value = State.CONNECTED
                updateNotification("Connected to $serverName")

                // Live TUN loop & Byte Counters simulation
                val pfd = vpnInterface ?: return@launch
                val inputStream = FileInputStream(pfd.fileDescriptor)
                val outputStream = FileOutputStream(pfd.fileDescriptor)
                val buffer = ByteBuffer.allocate(32768)

                var currentRx = 0L
                var currentTx = 0L

                while (isActive && _vpnState.value == State.CONNECTED) {
                    // Simulate light packet throughput monitoring
                    delay(1000)
                    currentRx += (12800..65400).random()
                    currentTx += (4500..28900).random()
                    _rxBytes.value = currentRx
                    _txBytes.value = currentTx
                }

            } catch (e: Exception) {
                Log.e("XrayVpnService", "VPN Error: ${e.message}", e)
                _vpnState.value = State.DISCONNECTED
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun stopVpnTunnel() {
        _vpnState.value = State.DISCONNECTING
        connectionJob?.cancel()
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _vpnState.value = State.DISCONNECTED
        _rxBytes.value = 0L
        _txBytes.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Xray VPN Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live Xray VPN status and traffic metrics"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, XrayVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Xray VPN")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        stopVpnTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
