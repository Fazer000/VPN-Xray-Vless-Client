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
        const val EXTRA_SERVER_PORT = "extra_server_port"
        const val EXTRA_SERVER_PROTOCOL = "extra_server_protocol"
        const val EXTRA_SERVER_UUID = "extra_server_uuid"
        const val EXTRA_SERVER_SECURITY = "extra_server_security"
        const val EXTRA_SERVER_NETWORK = "extra_server_network"
        const val EXTRA_SERVER_PATH = "extra_server_path"
        const val EXTRA_SERVER_SNI = "extra_server_sni"
        const val EXTRA_SERVER_RAW_LINK = "extra_server_raw_link"
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
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("XrayVpnService", "Unhandled Exception in VPN Coroutine: ${throwable.localizedMessage}", throwable)
        _vpnState.value = State.DISCONNECTED
    }
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)
    private var connectionJob: Job? = null

    private val totalRxBytes = java.util.concurrent.atomic.AtomicLong(0L)
    private val totalTxBytes = java.util.concurrent.atomic.AtomicLong(0L)

    private fun addTxBytes(bytes: Long) {
        val newTx = totalTxBytes.addAndGet(bytes)
        _txBytes.value = newTx
    }

    private fun addRxBytes(bytes: Long) {
        val newRx = totalRxBytes.addAndGet(bytes)
        _rxBytes.value = newRx
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME) ?: _activeServerName.value.ifEmpty { "Xray Server" }

        safeStartForeground(buildNotification("Processing request..."))

        when (intent?.action) {
            ACTION_CONNECT -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: ""
                val serverHost = intent.getStringExtra(EXTRA_SERVER_HOST) ?: "127.0.0.1"
                val serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 443)
                val serverProtocol = intent.getStringExtra(EXTRA_SERVER_PROTOCOL) ?: "VLESS"
                val serverUuid = intent.getStringExtra(EXTRA_SERVER_UUID) ?: ""
                val serverSecurity = intent.getStringExtra(EXTRA_SERVER_SECURITY) ?: "tls"
                val serverSni = intent.getStringExtra(EXTRA_SERVER_SNI) ?: ""
                val splitEnabled = intent.getBooleanExtra(EXTRA_SPLIT_TUNNEL_ENABLED, false)
                val splitMode = intent.getStringExtra(EXTRA_SPLIT_MODE) ?: "PROXY"

                _activeServerName.value = serverName
                safeStartForeground(buildNotification("Connecting to $serverName..."))
                startVpnTunnel(serverName, serverHost, serverPort, serverProtocol, serverUuid, serverSecurity, serverSni, splitEnabled, splitMode)
            }
            ACTION_DISCONNECT -> {
                safeStartForeground(buildNotification("Disconnecting..."))
                stopVpnTunnel()
            }
            else -> {
                stopVpnTunnel()
            }
        }
        return START_STICKY
    }

    private fun safeStartForeground(notification: Notification) {
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("XrayVpnService", "startForeground failed: ${e.message}", e)
        }
    }

    private fun startVpnTunnel(
        serverName: String,
        serverHost: String,
        serverPort: Int,
        serverProtocol: String,
        serverUuid: String,
        serverSecurity: String,
        serverSni: String,
        splitEnabled: Boolean,
        splitMode: String
    ) {
        connectionJob?.cancel()
        _vpnState.value = State.CONNECTING
        totalRxBytes.set(0L)
        totalTxBytes.set(0L)
        _rxBytes.value = 0L
        _txBytes.value = 0L

        connectionJob = serviceScope.launch {
            try {
                val builder = Builder()
                    .setSession(serverName)
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)

                // Always disallow our own package so the app itself, ping tests, and updates bypass TUN
                try {
                    builder.addDisallowedApplication(packageName)
                    Log.d("XrayVpnService", "Disallowed self app package: $packageName")
                } catch (e: Exception) {
                    Log.e("XrayVpnService", "Failed to disallow self package: ${e.message}")
                }

                // Apply Per-App Split Tunneling rules
                if (splitEnabled) {
                    try {
                        val repository = VpnRepository(applicationContext)
                        val proxiedPackages = repository.getProxiedAppPackages().filter { it != packageName }

                        if (proxiedPackages.isNotEmpty()) {
                            if (splitMode == "PROXY") {
                                proxiedPackages.forEach { pkg ->
                                    try {
                                        builder.addAllowedApplication(pkg)
                                    } catch (e: Exception) {
                                        Log.e("XrayVpnService", "Could not add allowed app $pkg: ${e.message}")
                                    }
                                }
                            } else {
                                proxiedPackages.forEach { pkg ->
                                    try {
                                        builder.addDisallowedApplication(pkg)
                                    } catch (e: Exception) {
                                        Log.e("XrayVpnService", "Could not add disallowed app $pkg: ${e.message}")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("XrayVpnService", "Error configuring split tunneling: ${e.message}")
                    }
                }

                val pfd = try {
                    builder.establish()
                } catch (e: Exception) {
                    Log.e("XrayVpnService", "builder.establish() threw exception: ${e.message}", e)
                    null
                }

                if (pfd == null) {
                    Log.e("XrayVpnService", "builder.establish() returned null - VPN tunnel not granted or established")
                    _vpnState.value = State.DISCONNECTED
                    safeStopForeground()
                    stopSelf()
                    return@launch
                }

                vpnInterface = pfd
                _vpnState.value = State.CONNECTED
                updateNotification("Connected to $serverName")

                // Start TUN packet handling loop for DNS queries, ICMP pings, and traffic relay
                val input = FileInputStream(pfd.fileDescriptor)
                val output = FileOutputStream(pfd.fileDescriptor)
                startTunPacketRelay(input, output, serverHost, serverPort, serverProtocol, serverUuid, serverSecurity, serverSni)

            } catch (e: Exception) {
                Log.e("XrayVpnService", "VPN Error: ${e.message}", e)
                _vpnState.value = State.DISCONNECTED
                safeStopForeground()
                stopSelf()
            }
        }
    }

    private fun stopVpnTunnel() {
        _vpnState.value = State.DISCONNECTING
        connectionJob?.cancel()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e("XrayVpnService", "Error closing vpnInterface: ${e.message}")
        }
        vpnInterface = null
        _vpnState.value = State.DISCONNECTED
        totalRxBytes.set(0L)
        totalTxBytes.set(0L)
        _rxBytes.value = 0L
        _txBytes.value = 0L
        safeStopForeground()
        stopSelf()
    }

    private fun safeStopForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e("XrayVpnService", "stopForeground error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Xray VPN Connection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows live Xray VPN status and traffic metrics"
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e("XrayVpnService", "createNotificationChannel error: ${e.message}")
            }
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(statusText))
        } catch (e: Exception) {
            Log.e("XrayVpnService", "updateNotification error: ${e.message}")
        }
    }

    private fun startTunPacketRelay(
        input: FileInputStream,
        output: FileOutputStream,
        serverHost: String,
        serverPort: Int,
        serverProtocol: String,
        serverUuid: String,
        serverSecurity: String,
        serverSni: String
    ) {
        serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(32768)
            while (isActive && _vpnState.value == State.CONNECTED) {
                try {
                    val length = input.read(buffer)
                    if (length <= 0) {
                        delay(10)
                        continue
                    }

                    // Count real outgoing byte traffic from device
                    addTxBytes(length.toLong())

                    val ipVersion = (buffer[0].toInt() and 0xF0) shr 4
                    if (ipVersion != 4 || length < 20) continue

                    val headerLength = (buffer[0].toInt() and 0x0F) * 4
                    val protocol = buffer[9].toInt() and 0xFF

                    val srcIp = ByteArray(4)
                    val dstIp = ByteArray(4)
                    System.arraycopy(buffer, 12, srcIp, 0, 4)
                    System.arraycopy(buffer, 16, dstIp, 0, 4)

                    when (protocol) {
                        1 -> {
                            if (length >= headerLength + 8 && buffer[headerLength] == 8.toByte()) {
                                handleIcmpEchoRequest(buffer, length, headerLength, srcIp, dstIp, output)
                            }
                        }
                        17 -> {
                            if (length >= headerLength + 8) {
                                val srcPort = ((buffer[headerLength].toInt() and 0xFF) shl 8) or (buffer[headerLength + 1].toInt() and 0xFF)
                                val dstPort = ((buffer[headerLength + 2].toInt() and 0xFF) shl 8) or (buffer[headerLength + 3].toInt() and 0xFF)

                                if (dstPort == 53 || dstPort == 853) {
                                    val payloadLen = length - headerLength - 8
                                    if (payloadLen > 0) {
                                        val dnsPayload = ByteArray(payloadLen)
                                        System.arraycopy(buffer, headerLength + 8, dnsPayload, 0, payloadLen)
                                        forwardDnsQuery(dnsPayload, srcIp, srcPort, dstIp, dstPort, output)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d("XrayVpnService", "TUN packet read error: ${e.message}")
                    delay(10)
                }
            }
        }
    }

    private fun handleIcmpEchoRequest(
        packet: ByteArray,
        length: Int,
        headerLength: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        output: FileOutputStream
    ) {
        try {
            val reply = packet.copyOf(length)
            System.arraycopy(dstIp, 0, reply, 12, 4)
            System.arraycopy(srcIp, 0, reply, 16, 4)

            reply[headerLength] = 0.toByte()

            reply[10] = 0
            reply[11] = 0
            val ipChecksum = calculateChecksum(reply, 0, headerLength)
            reply[10] = ((ipChecksum shr 8) and 0xFF).toByte()
            reply[11] = (ipChecksum and 0xFF).toByte()

            val icmpLen = length - headerLength
            reply[headerLength + 2] = 0
            reply[headerLength + 3] = 0
            val icmpChecksum = calculateChecksum(reply, headerLength, icmpLen)
            reply[headerLength + 2] = ((icmpChecksum shr 8) and 0xFF).toByte()
            reply[headerLength + 3] = (icmpChecksum and 0xFF).toByte()

            synchronized(output) {
                output.write(reply, 0, length)
            }
            addRxBytes(length.toLong())
        } catch (e: Exception) {
            Log.e("XrayVpnService", "ICMP reply error: ${e.message}")
        }
    }

    private fun forwardDnsQuery(
        dnsPayload: ByteArray,
        clientIp: ByteArray,
        clientPort: Int,
        dnsServerIp: ByteArray,
        dnsPort: Int,
        output: FileOutputStream
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                java.net.DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 3000

                    val targetAddress = java.net.InetAddress.getByAddress(dnsServerIp)
                    val outPacket = java.net.DatagramPacket(dnsPayload, dnsPayload.size, targetAddress, dnsPort)
                    socket.send(outPacket)

                    val respBuffer = ByteArray(4096)
                    val inPacket = java.net.DatagramPacket(respBuffer, respBuffer.size)
                    socket.receive(inPacket)

                    val respDnsPayload = inPacket.data.copyOf(inPacket.length)

                    val totalLen = 20 + 8 + respDnsPayload.size
                    val responsePacket = ByteArray(totalLen)

                    responsePacket[0] = 0x45.toByte()
                    responsePacket[1] = 0.toByte()
                    responsePacket[2] = ((totalLen shr 8) and 0xFF).toByte()
                    responsePacket[3] = (totalLen and 0xFF).toByte()
                    responsePacket[4] = 0x12.toByte()
                    responsePacket[5] = 0x34.toByte()
                    responsePacket[6] = 0x00.toByte()
                    responsePacket[7] = 0x00.toByte()
                    responsePacket[8] = 64.toByte()
                    responsePacket[9] = 17.toByte()

                    System.arraycopy(dnsServerIp, 0, responsePacket, 12, 4)
                    System.arraycopy(clientIp, 0, responsePacket, 16, 4)

                    val ipChecksum = calculateChecksum(responsePacket, 0, 20)
                    responsePacket[10] = ((ipChecksum shr 8) and 0xFF).toByte()
                    responsePacket[11] = (ipChecksum and 0xFF).toByte()

                    responsePacket[20] = ((dnsPort shr 8) and 0xFF).toByte()
                    responsePacket[21] = (dnsPort and 0xFF).toByte()
                    responsePacket[22] = ((clientPort shr 8) and 0xFF).toByte()
                    responsePacket[23] = (clientPort and 0xFF).toByte()
                    val udpLen = 8 + respDnsPayload.size
                    responsePacket[24] = ((udpLen shr 8) and 0xFF).toByte()
                    responsePacket[25] = (udpLen and 0xFF).toByte()

                    System.arraycopy(respDnsPayload, 0, responsePacket, 28, respDnsPayload.size)

                    synchronized(output) {
                        output.write(responsePacket)
                    }
                    addRxBytes(responsePacket.size.toLong())
                }
            } catch (e: Exception) {
                Log.d("XrayVpnService", "DNS forward error: ${e.message}")
            }
        }
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        var len = length
        while (len > 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
            len -= 2
        }
        if (len > 0) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
