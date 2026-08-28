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
import com.example.util.LogManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
                val serverNetwork = intent.getStringExtra(EXTRA_SERVER_NETWORK) ?: "tcp"
                val serverPath = intent.getStringExtra(EXTRA_SERVER_PATH) ?: ""
                val serverSni = intent.getStringExtra(EXTRA_SERVER_SNI) ?: ""
                val splitEnabled = intent.getBooleanExtra(EXTRA_SPLIT_TUNNEL_ENABLED, false)
                val splitMode = intent.getStringExtra(EXTRA_SPLIT_MODE) ?: "PROXY"

                _activeServerName.value = serverName
                safeStartForeground(buildNotification("Connecting to $serverName..."))
                startVpnTunnel(serverName, serverHost, serverPort, serverProtocol, serverUuid, serverSecurity, serverNetwork, serverPath, serverSni, splitEnabled, splitMode)
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
        serverNetwork: String,
        serverPath: String,
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

        LogManager.i("Service", "Starting VPN session: '$serverName'")
        LogManager.i("Service", "Target Server: $serverProtocol://$serverHost:$serverPort (Security: $serverSecurity, Net: $serverNetwork, SNI: $serverSni)")

        connectionJob = serviceScope.launch {
            try {
                LogManager.i("TUN", "Building TUN interface (IP: 10.0.0.2/24, MTU: 1500, DNS: 1.1.1.1, 8.8.8.8)")
                val builder = Builder()
                    .setSession(serverName)
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)

                // Apply Per-App Split Tunneling rules
                if (splitEnabled) {
                    try {
                        val repository = VpnRepository(applicationContext)
                        val proxiedPackages = repository.getProxiedAppPackages().filter { it != packageName }
                        LogManager.i("SplitTunnel", "Mode: $splitMode, App Count: ${proxiedPackages.size}")

                        if (proxiedPackages.isNotEmpty()) {
                            if (splitMode == "PROXY") {
                                proxiedPackages.forEach { pkg ->
                                    try {
                                        builder.addAllowedApplication(pkg)
                                    } catch (e: Exception) {
                                        LogManager.w("SplitTunnel", "Could not add allowed app $pkg: ${e.message}")
                                    }
                                }
                            } else {
                                try {
                                    builder.addDisallowedApplication(packageName)
                                } catch (_: Exception) {}
                                proxiedPackages.forEach { pkg ->
                                    try {
                                        builder.addDisallowedApplication(pkg)
                                    } catch (e: Exception) {
                                        LogManager.w("SplitTunnel", "Could not add disallowed app $pkg: ${e.message}")
                                    }
                                }
                            }
                        } else {
                            try {
                                builder.addDisallowedApplication(packageName)
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        LogManager.e("SplitTunnel", "Error configuring split tunneling: ${e.message}")
                    }
                } else {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: Exception) {}
                }

                val pfd = try {
                    builder.establish()
                } catch (e: Exception) {
                    LogManager.e("TUN", "builder.establish() threw exception: ${e.message}")
                    null
                }

                if (pfd == null) {
                    LogManager.e("TUN", "builder.establish() returned null - VPN permission not granted or rejected")
                    _vpnState.value = State.DISCONNECTED
                    safeStopForeground()
                    stopSelf()
                    return@launch
                }

                vpnInterface = pfd
                _vpnState.value = State.CONNECTED
                LogManager.i("Service", "VPN Tunnel established! Listening for IP traffic...")
                updateNotification("Connected to $serverName")

                // Start TUN packet handling loop for DNS queries, ICMP pings, and traffic relay
                val input = FileInputStream(pfd.fileDescriptor)
                val output = FileOutputStream(pfd.fileDescriptor)
                startTunPacketRelay(input, output, serverHost, serverPort, serverProtocol, serverUuid, serverSecurity, serverNetwork, serverPath, serverSni)

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

    private val tcpSessions = java.util.concurrent.ConcurrentHashMap<String, TcpSession>()

    private inner class TcpSession(
        val key: String,
        val srcPort: Int,
        val dstPort: Int,
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        var clientSeq: Long,
        var serverSeq: Long,
        var socket: Socket? = null,
        @Volatile var isConnected: Boolean = false,
        @Volatile var isClosed: Boolean = false,
        val pendingOutboundData: ByteArrayOutputStream = ByteArrayOutputStream()
    )

    private fun startTunPacketRelay(
        input: FileInputStream,
        output: FileOutputStream,
        serverHost: String,
        serverPort: Int,
        serverProtocol: String,
        serverUuid: String,
        serverSecurity: String,
        serverNetwork: String,
        serverPath: String,
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
                        1 -> { // ICMP Ping
                            if (length >= headerLength + 8 && buffer[headerLength] == 8.toByte()) {
                                handleIcmpEchoRequest(buffer, length, headerLength, srcIp, dstIp, output)
                            }
                        }
                        6 -> { // TCP
                            if (length >= headerLength + 20) {
                                val srcPort = ((buffer[headerLength].toInt() and 0xFF) shl 8) or (buffer[headerLength + 1].toInt() and 0xFF)
                                val dstPort = ((buffer[headerLength + 2].toInt() and 0xFF) shl 8) or (buffer[headerLength + 3].toInt() and 0xFF)
                                val seqVal = ((buffer[headerLength + 4].toLong() and 0xFF) shl 24) or
                                        ((buffer[headerLength + 5].toLong() and 0xFF) shl 16) or
                                        ((buffer[headerLength + 6].toLong() and 0xFF) shl 8) or
                                        (buffer[headerLength + 7].toLong() and 0xFF)
                                val ackVal = ((buffer[headerLength + 8].toLong() and 0xFF) shl 24) or
                                        ((buffer[headerLength + 9].toLong() and 0xFF) shl 16) or
                                        ((buffer[headerLength + 10].toLong() and 0xFF) shl 8) or
                                        (buffer[headerLength + 11].toLong() and 0xFF)
                                val tcpHeaderLength = ((buffer[headerLength + 12].toInt() and 0xF0) ushr 4) * 4
                                val flags = buffer[headerLength + 13].toInt() and 0xFF
                                val payloadLen = length - headerLength - tcpHeaderLength

                                handleTcpPacket(
                                    buffer, headerLength, tcpHeaderLength, srcIp, dstIp,
                                    srcPort, dstPort, seqVal, ackVal, flags, payloadLen, output,
                                    serverHost, serverPort, serverProtocol, serverUuid, serverSecurity, serverNetwork, serverPath, serverSni
                                )
                            }
                        }
                        17 -> { // UDP
                            if (length >= headerLength + 8) {
                                val srcPort = ((buffer[headerLength].toInt() and 0xFF) shl 8) or (buffer[headerLength + 1].toInt() and 0xFF)
                                val dstPort = ((buffer[headerLength + 2].toInt() and 0xFF) shl 8) or (buffer[headerLength + 3].toInt() and 0xFF)
                                val payloadLen = length - headerLength - 8

                                if (dstPort == 53 || dstPort == 853) {
                                    if (payloadLen > 0) {
                                        val dnsPayload = ByteArray(payloadLen)
                                        System.arraycopy(buffer, headerLength + 8, dnsPayload, 0, payloadLen)
                                        forwardDnsQuery(dnsPayload, srcIp, srcPort, dstIp, dstPort, output)
                                    }
                                } else if (payloadLen > 0) {
                                    val udpPayload = ByteArray(payloadLen)
                                    System.arraycopy(buffer, headerLength + 8, udpPayload, 0, payloadLen)
                                    forwardUdpPacket(udpPayload, srcIp, srcPort, dstIp, dstPort, output)
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

    private fun handleTcpPacket(
        buffer: ByteArray,
        headerLength: Int,
        tcpHeaderLength: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqVal: Long,
        ackVal: Long,
        flags: Int,
        payloadLen: Int,
        output: FileOutputStream,
        serverHost: String,
        serverPort: Int,
        serverProtocol: String,
        serverUuid: String,
        serverSecurity: String,
        serverNetwork: String,
        serverPath: String,
        serverSni: String
    ) {
        val srcIpStr = try { InetAddress.getByAddress(srcIp).hostAddress ?: "" } catch (_: Exception) { "" }
        val dstIpStr = try { InetAddress.getByAddress(dstIp).hostAddress ?: "" } catch (_: Exception) { "" }
        val key = "$srcIpStr:$srcPort->$dstIpStr:$dstPort"
        val existingSession = tcpSessions[key]

        val isSyn = (flags and 0x02) != 0
        val isRst = (flags and 0x04) != 0
        val isFin = (flags and 0x01) != 0

        if (isSyn) {
            if (existingSession != null && !existingSession.isClosed) {
                // Retransmitted SYN -> Re-send SYN-ACK
                val lastServerSeq = (existingSession.serverSeq - 1) and 0xFFFFFFFFL
                sendTcpPacket(output, dstIp, srcIp, dstPort, srcPort, lastServerSeq, existingSession.clientSeq, 0x12, null)
                return
            }

            val session = TcpSession(
                key = key,
                srcPort = srcPort,
                dstPort = dstPort,
                clientIp = srcIp,
                serverIp = dstIp,
                clientSeq = (seqVal + 1) and 0xFFFFFFFFL,
                serverSeq = 100000L
            )
            tcpSessions[key] = session

            // 1. Immediately send SYN-ACK (0x12) to local client so handshake completes instantly
            sendTcpPacket(output, dstIp, srcIp, dstPort, srcPort, session.serverSeq, session.clientSeq, 0x12, null)
            session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL

            // 2. Connect proxy socket asynchronously in background thread
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val targetHost = if (dstIpStr.isNotEmpty()) dstIpStr else "127.0.0.1"

                    val socket = connectProxySocket(
                        serverHost, serverPort, serverProtocol, serverUuid,
                        serverSecurity, serverNetwork, serverPath, serverSni,
                        targetHost, dstPort
                    )

                    synchronized(session) {
                        if (!session.isClosed) {
                            session.socket = socket
                            session.isConnected = true

                            // Flush any payload bytes buffered while proxy was establishing
                            val pendingBytes = session.pendingOutboundData.toByteArray()
                            if (pendingBytes.isNotEmpty()) {
                                try {
                                    socket.getOutputStream().write(pendingBytes)
                                    socket.getOutputStream().flush()
                                } catch (e: Exception) {
                                    Log.d("XrayVpnService", "Error writing pending bytes to proxy: ${e.message}")
                                }
                                session.pendingOutboundData.reset()
                            }
                        } else {
                            try { socket.close() } catch (_: Exception) {}
                            return@launch
                        }
                    }

                    launchSocketReader(session, output)
                } catch (e: Exception) {
                    Log.d("XrayVpnService", "TCP connect error to $dstIpStr:$dstPort: ${e.message}")
                    synchronized(session) {
                        if (!session.isClosed) {
                            session.isClosed = true
                            sendTcpPacket(output, dstIp, srcIp, dstPort, srcPort, session.serverSeq, session.clientSeq, 0x04, null)
                        }
                    }
                    tcpSessions.remove(key)
                }
            }
            return
        }

        if (existingSession == null) {
            if (!isRst) {
                sendTcpPacket(output, dstIp, srcIp, dstPort, srcPort, ackVal, (seqVal + 1) and 0xFFFFFFFFL, 0x04, null)
            }
            return
        }

        if (isRst) {
            synchronized(existingSession) {
                existingSession.isClosed = true
                try { existingSession.socket?.close() } catch (_: Exception) {}
            }
            tcpSessions.remove(key)
            return
        }

        if (isFin) {
            synchronized(existingSession) {
                existingSession.clientSeq = (seqVal + payloadLen.coerceAtLeast(1)) and 0xFFFFFFFFL
                sendTcpPacket(output, dstIp, srcIp, dstPort, srcPort, existingSession.serverSeq, existingSession.clientSeq, 0x11, null)
                existingSession.isClosed = true
                try { existingSession.socket?.close() } catch (_: Exception) {}
            }
            tcpSessions.remove(key)
            return
        }

        if (payloadLen > 0) {
            val payload = ByteArray(payloadLen)
            System.arraycopy(buffer, headerLength + tcpHeaderLength, payload, 0, payloadLen)

            synchronized(existingSession) {
                existingSession.clientSeq = (seqVal + payloadLen) and 0xFFFFFFFFL

                // Immediately ACK client payload
                sendTcpPacket(output, dstIp, srcIp, dstPort, srcPort, existingSession.serverSeq, existingSession.clientSeq, 0x10, null)

                if (existingSession.isConnected && !existingSession.isClosed) {
                    val sock = existingSession.socket
                    if (sock != null) {
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                sock.getOutputStream().write(payload)
                                sock.getOutputStream().flush()
                            } catch (e: Exception) {
                                Log.d("XrayVpnService", "TCP socket write error: ${e.message}")
                            }
                        }
                    }
                } else if (!existingSession.isClosed) {
                    // Proxy is still connecting -> Buffer payload
                    if (existingSession.pendingOutboundData.size() < 1024 * 1024) {
                        existingSession.pendingOutboundData.write(payload)
                    }
                }
            }
        }
    }

    private fun connectProxySocket(
        serverHost: String,
        serverPort: Int,
        serverProtocol: String,
        serverUuid: String,
        serverSecurity: String,
        serverNetwork: String,
        serverPath: String,
        serverSni: String,
        targetHost: String,
        targetPort: Int
    ): Socket {
        LogManager.d("Outbound", "Routing stream -> $serverProtocol://$serverHost:$serverPort -> $targetHost:$targetPort")
        return try {
            when (serverProtocol.uppercase()) {
                "VLESS" -> establishVlessConnection(serverHost, serverPort, serverUuid, serverSecurity, serverNetwork, serverPath, serverSni, targetHost, targetPort)
                "TROJAN" -> establishTrojanConnection(serverHost, serverPort, serverUuid, serverSecurity, serverNetwork, serverPath, serverSni, targetHost, targetPort)
                "SOCKS", "SOCKS5" -> establishSocks5Connection(serverHost, serverPort, serverUuid, targetHost, targetPort)
                "VMESS" -> {
                    try {
                        establishVlessConnection(serverHost, serverPort, serverUuid, serverSecurity, serverNetwork, serverPath, serverSni, targetHost, targetPort)
                    } catch (_: Exception) {
                        establishTrojanConnection(serverHost, serverPort, serverUuid, serverSecurity, serverNetwork, serverPath, serverSni, targetHost, targetPort)
                    }
                }
                else -> {
                    try {
                        establishVlessConnection(serverHost, serverPort, serverUuid, serverSecurity, serverNetwork, serverPath, serverSni, targetHost, targetPort)
                    } catch (_: Exception) {
                        establishDirectConnection(targetHost, targetPort)
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e("Outbound", "Proxy connect failed for $serverProtocol ($serverHost:$serverPort) -> $targetHost:$targetPort: ${e.message}")
            establishDirectConnection(targetHost, targetPort)
        }
    }

    private fun establishDirectConnection(targetHost: String, targetPort: Int): Socket {
        val socket = Socket()
        protect(socket)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        val targetAddr = InetAddress.getByName(targetHost)
        socket.connect(InetSocketAddress(targetAddr, targetPort), 5000)
        return socket
    }

    private fun establishVlessConnection(
        serverHost: String,
        serverPort: Int,
        serverUuid: String,
        serverSecurity: String,
        serverNetwork: String,
        serverPath: String,
        serverSni: String,
        targetHost: String,
        targetPort: Int
    ): Socket {
        val rawSocket = Socket()
        protect(rawSocket)
        rawSocket.tcpNoDelay = true
        rawSocket.keepAlive = true
        rawSocket.connect(InetSocketAddress(serverHost, serverPort), 7000)

        val tlsSocket = if (serverSecurity.equals("tls", ignoreCase = true) || serverSecurity.equals("reality", ignoreCase = true) || serverPort == 443) {
            createTlsSocket(rawSocket, serverHost, serverPort, serverSni)
        } else {
            rawSocket
        }

        val socket = if (serverNetwork.equals("ws", ignoreCase = true)) {
            performWsUpgrade(tlsSocket, serverHost, serverPath, serverSni)
            WebSocketStreamSocket(tlsSocket)
        } else {
            tlsSocket
        }

        val out = socket.getOutputStream()
        val bos = ByteArrayOutputStream()

        bos.write(0) // Version 0
        bos.write(parseUuidToBytes(serverUuid))
        bos.write(0) // Add length 0
        bos.write(1) // Command: 1 = TCP

        writeAddressAndPort(bos, targetHost, targetPort)
        out.write(bos.toByteArray())
        out.flush()

        return VlessStreamSocket(socket)
    }

    private fun establishTrojanConnection(
        serverHost: String,
        serverPort: Int,
        password: String,
        serverSecurity: String,
        serverNetwork: String,
        serverPath: String,
        serverSni: String,
        targetHost: String,
        targetPort: Int
    ): Socket {
        val rawSocket = Socket()
        protect(rawSocket)
        rawSocket.tcpNoDelay = true
        rawSocket.keepAlive = true
        rawSocket.connect(InetSocketAddress(serverHost, serverPort), 7000)

        val tlsSocket = if (serverSecurity.equals("none", ignoreCase = true)) {
            rawSocket
        } else {
            createTlsSocket(rawSocket, serverHost, serverPort, serverSni)
        }

        val socket = if (serverNetwork.equals("ws", ignoreCase = true)) {
            performWsUpgrade(tlsSocket, serverHost, serverPath, serverSni)
            WebSocketStreamSocket(tlsSocket)
        } else {
            tlsSocket
        }

        val out = socket.getOutputStream()
        val bos = ByteArrayOutputStream()

        val hexPass = sha224Hex(password)
        bos.write(hexPass.toByteArray(Charsets.US_ASCII))
        bos.write(byteArrayOf(0x0D, 0x0A))
        bos.write(1) // Command: 1 = TCP

        val ipBytes = try { InetAddress.getByName(targetHost).address } catch (e: Exception) { null }
        if (ipBytes != null && ipBytes.size == 4) {
            bos.write(0x01)
            bos.write(ipBytes)
        } else if (ipBytes != null && ipBytes.size == 16) {
            bos.write(0x04)
            bos.write(ipBytes)
        } else {
            bos.write(0x03)
            val domainBytes = targetHost.toByteArray(Charsets.UTF_8)
            bos.write(domainBytes.size)
            bos.write(domainBytes)
        }
        bos.write((targetPort ushr 8) and 0xFF)
        bos.write(targetPort and 0xFF)
        bos.write(byteArrayOf(0x0D, 0x0A))

        out.write(bos.toByteArray())
        out.flush()

        return socket
    }

    private fun establishSocks5Connection(
        serverHost: String,
        serverPort: Int,
        userInfo: String,
        targetHost: String,
        targetPort: Int
    ): Socket {
        val socket = Socket()
        protect(socket)
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(serverHost, serverPort), 7000)

        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        if (userInfo.contains(":")) {
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        out.flush()

        val resp = ByteArray(2)
        input.read(resp)
        if (resp[1] == 0x02.toByte() && userInfo.contains(":")) {
            val parts = userInfo.split(":", limit = 2)
            val user = parts[0].toByteArray(Charsets.UTF_8)
            val pass = parts[1].toByteArray(Charsets.UTF_8)
            val authBos = ByteArrayOutputStream()
            authBos.write(0x01)
            authBos.write(user.size)
            authBos.write(user)
            authBos.write(pass.size)
            authBos.write(pass)
            out.write(authBos.toByteArray())
            out.flush()

            val authResp = ByteArray(2)
            input.read(authResp)
            if (authResp[1] != 0x00.toByte()) {
                throw Exception("SOCKS5 Auth failed")
            }
        }

        val connBos = ByteArrayOutputStream()
        connBos.write(0x05)
        connBos.write(0x01) // CONNECT
        connBos.write(0x00) // RSV

        val ipBytes = try { InetAddress.getByName(targetHost).address } catch (e: Exception) { null }
        if (ipBytes != null && ipBytes.size == 4) {
            connBos.write(0x01)
            connBos.write(ipBytes)
        } else {
            connBos.write(0x03)
            val domainBytes = targetHost.toByteArray(Charsets.UTF_8)
            connBos.write(domainBytes.size)
            connBos.write(domainBytes)
        }
        connBos.write((targetPort ushr 8) and 0xFF)
        connBos.write(targetPort and 0xFF)

        out.write(connBos.toByteArray())
        out.flush()

        val reply = ByteArray(10)
        input.read(reply)
        if (reply[1] != 0x00.toByte()) {
            throw Exception("SOCKS5 Connect rejected code: ${reply[1]}")
        }

        return socket
    }

    private fun createTlsSocket(plainSocket: Socket, host: String, port: Int, sniHost: String?): Socket {
        val sslContext = SSLContext.getInstance("TLS")
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        val factory = sslContext.socketFactory
        val sslSocket = factory.createSocket(plainSocket, host, port, true) as SSLSocket

        val sni = if (!sniHost.isNullOrBlank()) sniHost else host
        try {
            val sslParams = sslSocket.sslParameters
            sslParams.serverNames = listOf(javax.net.ssl.SNIHostName(sni))
            sslSocket.sslParameters = sslParams
        } catch (e: Exception) {
            Log.w("XrayVpnService", "Failed to set SNI hostname: ${e.message}")
        }

        sslSocket.startHandshake()
        return sslSocket
    }

    private fun readHttpResponseHeader(input: InputStream): String {
        val bos = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            bos.write(b)
            if (state == 0 && b == 13) state = 1
            else if (state == 1 && b == 10) state = 2
            else if (state == 2 && b == 13) state = 3
            else if (state == 3 && b == 10) break
            else if (b == 13) state = 1
            else state = 0
        }
        return bos.toString("UTF-8")
    }

    private fun performWsUpgrade(socket: Socket, host: String, path: String, sni: String) {
        val cleanPath = try { java.net.URLDecoder.decode(path, "UTF-8") } catch (_: Exception) { path }
        val wsPath = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
        val wsHost = if (sni.isNotEmpty()) sni else host
        val key = android.util.Base64.encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)

        val req = "GET $wsPath HTTP/1.1\r\n" +
                "Host: $wsHost\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\r\n\r\n"

        val out = socket.getOutputStream()
        out.write(req.toByteArray(Charsets.UTF_8))
        out.flush()

        val headerStr = readHttpResponseHeader(socket.getInputStream())
        if (!headerStr.contains("101")) {
            val firstLine = headerStr.lines().firstOrNull() ?: ""
            throw Exception("WebSocket Handshake failed: $firstLine")
        }
    }

    private fun parseUuidToBytes(uuidStr: String): ByteArray {
        return try {
            val clean = uuidStr.replace("-", "")
            if (clean.length == 32) {
                val bytes = ByteArray(16)
                for (i in 0 until 16) {
                    bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                bytes
            } else {
                val uuid = UUID.fromString(uuidStr)
                val bb = ByteBuffer.allocate(16)
                bb.putLong(uuid.mostSignificantBits)
                bb.putLong(uuid.leastSignificantBits)
                bb.array()
            }
        } catch (_: Exception) {
            ByteArray(16)
        }
    }

    private fun writeAddressAndPort(bos: ByteArrayOutputStream, host: String, port: Int) {
        // VLESS Specification: Port (2 bytes uint16) comes BEFORE Address Type
        bos.write((port ushr 8) and 0xFF)
        bos.write(port and 0xFF)

        val ipBytes = try { InetAddress.getByName(host).address } catch (_: Exception) { null }
        if (ipBytes != null && ipBytes.size == 4) {
            bos.write(1) // IPv4
            bos.write(ipBytes)
        } else if (ipBytes != null && ipBytes.size == 16) {
            bos.write(3) // IPv6
            bos.write(ipBytes)
        } else {
            bos.write(2) // Domain
            val domainBytes = host.toByteArray(Charsets.UTF_8)
            bos.write(domainBytes.size)
            bos.write(domainBytes)
        }
    }

    private fun sha224Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-224")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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
            var dohSuccess = false
            // Try Cloudflare DoH first
            try {
                val reqBody = dnsPayload.toRequestBody("application/dns-message".toMediaType())
                val request = Request.Builder()
                    .url("https://1.1.1.1/dns-query")
                    .post(reqBody)
                    .header("Accept", "application/dns-message")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val respDnsPayload = response.body?.bytes()
                    if (respDnsPayload != null && respDnsPayload.isNotEmpty()) {
                        sendUdpPacketToTun(output, dnsServerIp, dnsPort, clientIp, clientPort, respDnsPayload)
                        dohSuccess = true
                        LogManager.d("DNS", "Resolved DNS via Cloudflare DoH (size: ${respDnsPayload.size} bytes)")
                    }
                }
            } catch (e: Exception) {
                LogManager.d("DNS", "Cloudflare DoH error: ${e.message}")
            }

            // Try Google DoH if Cloudflare fails
            if (!dohSuccess) {
                try {
                    val reqBody = dnsPayload.toRequestBody("application/dns-message".toMediaType())
                    val request = Request.Builder()
                        .url("https://dns.google/dns-query")
                        .post(reqBody)
                        .header("Accept", "application/dns-message")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()

                    val response = okHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val respDnsPayload = response.body?.bytes()
                        if (respDnsPayload != null && respDnsPayload.isNotEmpty()) {
                            sendUdpPacketToTun(output, dnsServerIp, dnsPort, clientIp, clientPort, respDnsPayload)
                            dohSuccess = true
                            LogManager.d("DNS", "Resolved DNS via Google DoH (size: ${respDnsPayload.size} bytes)")
                        }
                    }
                } catch (e: Exception) {
                    LogManager.d("DNS", "Google DoH error: ${e.message}")
                }
            }

            // Fallback to standard UDP socket if DoH failed
            if (!dohSuccess) {
                try {
                    java.net.DatagramSocket().use { socket ->
                        protect(socket)
                        socket.soTimeout = 3000
                        val targetAddress = InetAddress.getByAddress(dnsServerIp)
                        val outPacket = java.net.DatagramPacket(dnsPayload, dnsPayload.size, targetAddress, dnsPort)
                        socket.send(outPacket)

                        val respBuffer = ByteArray(4096)
                        val inPacket = java.net.DatagramPacket(respBuffer, respBuffer.size)
                        socket.receive(inPacket)

                        val respDnsPayload = inPacket.data.copyOf(inPacket.length)
                        sendUdpPacketToTun(output, dnsServerIp, dnsPort, clientIp, clientPort, respDnsPayload)
                        LogManager.d("DNS", "Resolved DNS via UDP fallback")
                    }
                } catch (e: Exception) {
                    LogManager.w("DNS", "UDP DNS fallback failed: ${e.message}")
                }
            }
        }
    }

    private fun sendUdpPacketToTun(
        output: FileOutputStream,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray
    ) {
        val totalLen = 20 + 8 + payload.size
        val responsePacket = ByteArray(totalLen)

        responsePacket[0] = 0x45.toByte()
        responsePacket[1] = 0.toByte()
        responsePacket[2] = ((totalLen ushr 8) and 0xFF).toByte()
        responsePacket[3] = (totalLen and 0xFF).toByte()
        responsePacket[4] = 0x12.toByte()
        responsePacket[5] = 0x34.toByte()
        responsePacket[6] = 0x00.toByte()
        responsePacket[7] = 0x00.toByte()
        responsePacket[8] = 64.toByte()
        responsePacket[9] = 17.toByte() // UDP

        System.arraycopy(srcIp, 0, responsePacket, 12, 4)
        System.arraycopy(dstIp, 0, responsePacket, 16, 4)

        val ipChecksum = calculateChecksum(responsePacket, 0, 20)
        responsePacket[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        responsePacket[11] = (ipChecksum and 0xFF).toByte()

        responsePacket[20] = ((srcPort ushr 8) and 0xFF).toByte()
        responsePacket[21] = (srcPort and 0xFF).toByte()
        responsePacket[22] = ((dstPort ushr 8) and 0xFF).toByte()
        responsePacket[23] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + payload.size
        responsePacket[24] = ((udpLen ushr 8) and 0xFF).toByte()
        responsePacket[25] = (udpLen and 0xFF).toByte()

        System.arraycopy(payload, 0, responsePacket, 28, payload.size)

        synchronized(output) {
            try {
                output.write(responsePacket)
            } catch (e: Exception) {
                Log.d("XrayVpnService", "Write UDP packet to TUN error: ${e.message}")
            }
        }
        addRxBytes(responsePacket.size.toLong())
    }

    private fun launchSocketReader(session: TcpSession, output: FileOutputStream) {
        serviceScope.launch(Dispatchers.IO) {
            val socket = session.socket ?: return@launch
            val buffer = ByteArray(16384)
            val maxMss = 1360 // Safe MSS (1360 payload + 40 headers = 1400 <= 1500 TUN MTU)
            try {
                val input = socket.getInputStream()
                while (isActive && session.isConnected && !session.isClosed && !socket.isClosed) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    addRxBytes(read.toLong())

                    var offset = 0
                    while (offset < read) {
                        val chunkSize = Math.min(read - offset, maxMss)
                        val chunk = buffer.copyOfRange(offset, offset + chunkSize)
                        synchronized(session) {
                            if (!session.isClosed) {
                                sendTcpPacket(output, session.serverIp, session.clientIp, session.dstPort, session.srcPort, session.serverSeq, session.clientSeq, 0x18, chunk)
                                session.serverSeq = (session.serverSeq + chunkSize) and 0xFFFFFFFFL
                            }
                        }
                        offset += chunkSize
                    }
                }
            } catch (_: Exception) {
            } finally {
                synchronized(session) {
                    if (!session.isClosed) {
                        session.isClosed = true
                        sendTcpPacket(output, session.serverIp, session.clientIp, session.dstPort, session.srcPort, session.serverSeq, session.clientSeq, 0x11, null)
                        session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
                    }
                }
                try { socket.close() } catch (_: Exception) {}
                tcpSessions.remove(session.key)
            }
        }
    }

    private fun sendTcpPacket(
        output: FileOutputStream,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        payload: ByteArray?
    ) {
        val payloadSize = payload?.size ?: 0
        val totalLen = 20 + 20 + payloadSize
        val packet = ByteArray(totalLen)

        // IPv4 Header
        packet[0] = 0x45.toByte()
        packet[1] = 0.toByte()
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x12.toByte()
        packet[5] = 0x34.toByte()
        packet[6] = 0x40.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()
        packet[9] = 6.toByte() // TCP
        packet[10] = 0.toByte()
        packet[11] = 0.toByte()
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipChecksum = calculateChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // TCP Header
        packet[20] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort ushr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()

        packet[24] = ((seqNum ushr 24) and 0xFF).toByte()
        packet[25] = ((seqNum ushr 16) and 0xFF).toByte()
        packet[26] = ((seqNum ushr 8) and 0xFF).toByte()
        packet[27] = (seqNum and 0xFF).toByte()

        packet[28] = ((ackNum ushr 24) and 0xFF).toByte()
        packet[29] = ((ackNum ushr 16) and 0xFF).toByte()
        packet[30] = ((ackNum ushr 8) and 0xFF).toByte()
        packet[31] = (ackNum and 0xFF).toByte()

        packet[32] = 0x50.toByte()
        packet[33] = flags.toByte()
        packet[34] = 0xFF.toByte()
        packet[35] = 0xFF.toByte()
        packet[36] = 0.toByte()
        packet[37] = 0.toByte()
        packet[38] = 0.toByte()
        packet[39] = 0.toByte()

        if (payload != null && payloadSize > 0) {
            System.arraycopy(payload, 0, packet, 40, payloadSize)
        }

        val tcpLen = 20 + payloadSize
        val tcpChecksum = calculateTcpChecksum(srcIp, dstIp, packet, 20, tcpLen)
        packet[36] = ((tcpChecksum ushr 8) and 0xFF).toByte()
        packet[37] = (tcpChecksum and 0xFF).toByte()

        synchronized(output) {
            try {
                output.write(packet)
            } catch (e: Exception) {
                Log.d("XrayVpnService", "Write to TUN error: ${e.message}")
            }
        }
    }

    private fun calculateTcpChecksum(srcIp: ByteArray, dstIp: ByteArray, packet: ByteArray, tcpOffset: Int, tcpLen: Int): Int {
        var sum = 0L
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += 6
        sum += tcpLen

        var i = tcpOffset
        var len = tcpLen
        while (len > 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
            len -= 2
        }
        if (len > 0) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun forwardUdpPacket(
        udpPayload: ByteArray,
        clientIp: ByteArray,
        clientPort: Int,
        serverIp: ByteArray,
        serverPort: Int,
        output: FileOutputStream
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                java.net.DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 4000
                    val targetAddress = InetAddress.getByAddress(serverIp)
                    val outPacket = java.net.DatagramPacket(udpPayload, udpPayload.size, targetAddress, serverPort)
                    socket.send(outPacket)

                    val respBuffer = ByteArray(8192)
                    val inPacket = java.net.DatagramPacket(respBuffer, respBuffer.size)
                    socket.receive(inPacket)

                    val respPayload = inPacket.data.copyOf(inPacket.length)
                    sendUdpPacketToTun(output, serverIp, serverPort, clientIp, clientPort, respPayload)
                }
            } catch (_: Exception) {
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
            reply[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
            reply[11] = (ipChecksum and 0xFF).toByte()

            val icmpLen = length - headerLength
            reply[headerLength + 2] = 0
            reply[headerLength + 3] = 0
            val icmpChecksum = calculateChecksum(reply, headerLength, icmpLen)
            reply[headerLength + 2] = ((icmpChecksum ushr 8) and 0xFF).toByte()
            reply[headerLength + 3] = (icmpChecksum and 0xFF).toByte()

            synchronized(output) {
                output.write(reply, 0, length)
            }
            addRxBytes(length.toLong())
        } catch (e: Exception) {
            Log.e("XrayVpnService", "ICMP reply error: ${e.message}")
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
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

class VlessStreamSocket(private val delegate: Socket) : Socket() {
    private val inStream = VlessInputStream(delegate.getInputStream())

    override fun getInputStream(): InputStream = inStream
    override fun getOutputStream(): OutputStream = delegate.getOutputStream()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isClosed(): Boolean = delegate.isClosed
    override fun close() {
        try { delegate.close() } catch (_: Exception) {}
    }
}

class VlessInputStream(private val delegateIn: InputStream) : InputStream() {
    private var headerRead = false

    override fun read(): Int {
        val b = ByteArray(1)
        val r = read(b, 0, 1)
        return if (r > 0) b[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (!headerRead) {
            headerRead = true
            try {
                val respHeader = ByteArray(2)
                var read = 0
                while (read < 2) {
                    val count = delegateIn.read(respHeader, read, 2 - read)
                    if (count < 0) break
                    read += count
                }
                if (read >= 2) {
                    val addLen = respHeader[1].toInt() and 0xFF
                    if (addLen > 0) {
                        val addBytes = ByteArray(addLen)
                        var addRead = 0
                        while (addRead < addLen) {
                            val c = delegateIn.read(addBytes, addRead, addLen - addRead)
                            if (c < 0) break
                            addRead += c
                        }
                    }
                }
            } catch (e: Exception) {
                LogManager.w("VLESS", "VLESS header read error: ${e.message}")
            }
        }
        return delegateIn.read(b, off, len)
    }
}

class WebSocketStreamSocket(private val delegate: Socket) : Socket() {
    private val inStream = WebSocketInputStream(delegate.getInputStream(), delegate.getOutputStream())
    private val outStream = WebSocketOutputStream(delegate.getOutputStream())

    override fun getInputStream(): InputStream = inStream
    override fun getOutputStream(): OutputStream = outStream
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isClosed(): Boolean = delegate.isClosed
    override fun close() {
        try { delegate.close() } catch (_: Exception) {}
    }
}

class WebSocketOutputStream(private val delegateOut: OutputStream) : OutputStream() {
    private val random = java.security.SecureRandom()

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val bos = ByteArrayOutputStream()
        bos.write(0x82) // FIN + Opcode 2 (Binary)

        val maskKey = ByteArray(4)
        random.nextBytes(maskKey)

        if (len <= 125) {
            bos.write(0x80 or len)
        } else if (len <= 65535) {
            bos.write(0x80 or 126)
            bos.write((len ushr 8) and 0xFF)
            bos.write(len and 0xFF)
        } else {
            bos.write(0x80 or 127)
            for (i in 7 downTo 0) {
                bos.write(((len.toLong() ushr (i * 8)) and 0xFF).toInt())
            }
        }

        bos.write(maskKey)

        for (i in 0 until len) {
            val masked = (b[off + i].toInt() xor maskKey[i % 4].toInt()).toByte()
            bos.write(masked.toInt())
        }

        synchronized(delegateOut) {
            delegateOut.write(bos.toByteArray())
            delegateOut.flush()
        }
    }

    override fun flush() {
        delegateOut.flush()
    }
}

class WebSocketInputStream(
    private val delegateIn: InputStream,
    private val delegateOut: OutputStream? = null
) : InputStream() {
    private var bufferBytes = ByteArray(0)
    private var bufferPos = 0
    private val random = java.security.SecureRandom()

    override fun read(): Int {
        val b = ByteArray(1)
        val read = read(b, 0, 1)
        return if (read > 0) b[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (bufferPos >= bufferBytes.size) {
            if (!readNextFrame()) return -1
        }
        val available = bufferBytes.size - bufferPos
        val toCopy = Math.min(len, available)
        System.arraycopy(bufferBytes, bufferPos, b, off, toCopy)
        bufferPos += toCopy
        return toCopy
    }

    private fun sendPongFrame(payload: ByteArray) {
        if (delegateOut == null) return
        try {
            val bos = ByteArrayOutputStream()
            bos.write(0x8A) // FIN + Opcode 10 (Pong)
            val maskKey = ByteArray(4)
            random.nextBytes(maskKey)
            val len = payload.size
            if (len <= 125) {
                bos.write(0x80 or len)
            } else {
                bos.write(0x80 or 126)
                bos.write((len ushr 8) and 0xFF)
                bos.write(len and 0xFF)
            }
            bos.write(maskKey)
            for (i in 0 until len) {
                val masked = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                bos.write(masked.toInt())
            }
            synchronized(delegateOut) {
                delegateOut.write(bos.toByteArray())
                delegateOut.flush()
            }
        } catch (_: Exception) {}
    }

    private fun readNextFrame(): Boolean {
        bufferPos = 0
        while (true) {
            val b0 = delegateIn.read()
            if (b0 == -1) return false
            val opcode = b0 and 0x0F

            val b1 = delegateIn.read()
            if (b1 == -1) return false
            val isMasked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val b2 = delegateIn.read()
                val b3 = delegateIn.read()
                if (b2 == -1 || b3 == -1) return false
                payloadLen = (((b2 and 0xFF) shl 8) or (b3 and 0xFF)).toLong()
            } else if (payloadLen == 127L) {
                var len = 0L
                for (i in 0 until 8) {
                    val b = delegateIn.read()
                    if (b == -1) return false
                    len = (len shl 8) or (b and 0xFF).toLong()
                }
                payloadLen = len
            }

            val maskKey = ByteArray(4)
            if (isMasked) {
                var readMask = 0
                while (readMask < 4) {
                    val r = delegateIn.read(maskKey, readMask, 4 - readMask)
                    if (r <= 0) return false
                    readMask += r
                }
            }

            val payload = ByteArray(payloadLen.toInt())
            var readPayload = 0
            while (readPayload < payloadLen.toInt()) {
                val r = delegateIn.read(payload, readPayload, payloadLen.toInt() - readPayload)
                if (r <= 0) return false
                readPayload += r
            }

            if (isMasked) {
                for (i in 0 until payload.size) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }

            if (opcode == 0x8) { // Close
                return false
            } else if (opcode == 0x9) { // Ping
                sendPongFrame(payload)
                continue
            } else if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) { // Text/Binary/Continuation
                bufferBytes = payload
                return true
            }
        }
    }
}
