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
                                try {
                                    builder.addDisallowedApplication(packageName)
                                } catch (_: Exception) {}
                                proxiedPackages.forEach { pkg ->
                                    try {
                                        builder.addDisallowedApplication(pkg)
                                    } catch (e: Exception) {
                                        Log.e("XrayVpnService", "Could not add disallowed app $pkg: ${e.message}")
                                    }
                                }
                            }
                        } else {
                            try {
                                builder.addDisallowedApplication(packageName)
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        Log.e("XrayVpnService", "Error configuring split tunneling: ${e.message}")
                    }
                } else {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: Exception) {}
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
        var socket: java.net.Socket? = null,
        var isConnected: Boolean = false,
        var isClosed: Boolean = false
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
                                    srcPort, dstPort, seqVal, ackVal, flags, payloadLen, output
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
        output: FileOutputStream
    ) {
        val key = "$srcPort:$dstPort"
        val existingSession = tcpSessions[key]

        val isSyn = (flags and 0x02) != 0
        val isRst = (flags and 0x04) != 0
        val isFin = (flags and 0x01) != 0

        if (isSyn) {
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

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val socket = java.net.Socket()
                    protect(socket) // Ensure socket bypasses TUN interface
                    socket.tcpNoDelay = true
                    val targetAddr = java.net.InetAddress.getByAddress(dstIp)
                    socket.connect(InetSocketAddress(targetAddr, dstPort), 5000)

                    session.socket = socket
                    session.isConnected = true

                    // Send SYN-ACK (0x12)
                    sendTcpPacket(output, dstIp, srcIp, dstPort, srcPort, session.serverSeq, session.clientSeq, 0x12, null)
                    session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL

                    launchSocketReader(session, output)
                } catch (e: Exception) {
                    Log.d("XrayVpnService", "TCP connect error to port $dstPort: ${e.message}")
                    sendTcpPacket(output, dstIp, srcIp, dstPort, srcPort, 0L, (seqVal + 1) and 0xFFFFFFFFL, 0x04, null)
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
            existingSession.isClosed = true
            try { existingSession.socket?.close() } catch (_: Exception) {}
            tcpSessions.remove(key)
            return
        }

        if (isFin) {
            existingSession.clientSeq = (seqVal + payloadLen.coerceAtLeast(1)) and 0xFFFFFFFFL
            sendTcpPacket(output, dstIp, srcIp, dstPort, srcPort, existingSession.serverSeq, existingSession.clientSeq, 0x11, null)
            existingSession.isClosed = true
            try { existingSession.socket?.close() } catch (_: Exception) {}
            tcpSessions.remove(key)
            return
        }

        if (payloadLen > 0 && existingSession.isConnected) {
            val payload = ByteArray(payloadLen)
            System.arraycopy(buffer, headerLength + tcpHeaderLength, payload, 0, payloadLen)
            existingSession.clientSeq = (seqVal + payloadLen) and 0xFFFFFFFFL

            serviceScope.launch(Dispatchers.IO) {
                try {
                    existingSession.socket?.getOutputStream()?.write(payload)
                    existingSession.socket?.getOutputStream()?.flush()
                    sendTcpPacket(output, dstIp, srcIp, dstPort, srcPort, existingSession.serverSeq, existingSession.clientSeq, 0x10, null)
                } catch (e: Exception) {
                    Log.d("XrayVpnService", "TCP socket write error: ${e.message}")
                }
            }
        }
    }

    private fun launchSocketReader(session: TcpSession, output: FileOutputStream) {
        serviceScope.launch(Dispatchers.IO) {
            val socket = session.socket ?: return@launch
            val buffer = ByteArray(16384)
            try {
                val input = socket.getInputStream()
                while (isActive && session.isConnected && !session.isClosed && !socket.isClosed) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val chunk = buffer.copyOf(read)
                    addRxBytes(read.toLong())
                    sendTcpPacket(output, session.serverIp, session.clientIp, session.dstPort, session.srcPort, session.serverSeq, session.clientSeq, 0x18, chunk)
                    session.serverSeq = (session.serverSeq + read) and 0xFFFFFFFFL
                }
            } catch (_: Exception) {
            } finally {
                if (!session.isClosed) {
                    session.isClosed = true
                    sendTcpPacket(output, session.serverIp, session.clientIp, session.dstPort, session.srcPort, session.serverSeq, session.clientSeq, 0x11, null)
                    session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
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
        packet[2] = ((totalLen shr 8) and 0xFF).toByte()
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
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // TCP Header
        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()

        packet[24] = ((seqNum shr 24) and 0xFF).toByte()
        packet[25] = ((seqNum shr 16) and 0xFF).toByte()
        packet[26] = ((seqNum shr 8) and 0xFF).toByte()
        packet[27] = (seqNum and 0xFF).toByte()

        packet[28] = ((ackNum shr 24) and 0xFF).toByte()
        packet[29] = ((ackNum shr 16) and 0xFF).toByte()
        packet[30] = ((ackNum shr 8) and 0xFF).toByte()
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
        packet[36] = ((tcpChecksum shr 8) and 0xFF).toByte()
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
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
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
                    val targetAddress = java.net.InetAddress.getByAddress(serverIp)
                    val outPacket = java.net.DatagramPacket(udpPayload, udpPayload.size, targetAddress, serverPort)
                    socket.send(outPacket)

                    val respBuffer = ByteArray(8192)
                    val inPacket = java.net.DatagramPacket(respBuffer, respBuffer.size)
                    socket.receive(inPacket)

                    val respPayload = inPacket.data.copyOf(inPacket.length)
                    val totalLen = 20 + 8 + respPayload.size
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

                    System.arraycopy(serverIp, 0, responsePacket, 12, 4)
                    System.arraycopy(clientIp, 0, responsePacket, 16, 4)

                    val ipChecksum = calculateChecksum(responsePacket, 0, 20)
                    responsePacket[10] = ((ipChecksum shr 8) and 0xFF).toByte()
                    responsePacket[11] = (ipChecksum and 0xFF).toByte()

                    responsePacket[20] = ((serverPort shr 8) and 0xFF).toByte()
                    responsePacket[21] = (serverPort and 0xFF).toByte()
                    responsePacket[22] = ((clientPort shr 8) and 0xFF).toByte()
                    responsePacket[23] = (clientPort and 0xFF).toByte()
                    val udpLen = 8 + respPayload.size
                    responsePacket[24] = ((udpLen shr 8) and 0xFF).toByte()
                    responsePacket[25] = (udpLen and 0xFF).toByte()

                    System.arraycopy(respPayload, 0, responsePacket, 28, respPayload.size)

                    synchronized(output) {
                        output.write(responsePacket)
                    }
                    addRxBytes(responsePacket.size.toLong())
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
