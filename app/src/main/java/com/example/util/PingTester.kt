package com.example.util

import com.example.data.model.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object PingTester {

    /**
     * Measures actual proxy latency (handshake RTT) to server host:port in milliseconds.
     * Performs TLS / REALITY / WS handshake probe to verify the server is actually a working proxy node.
     * Returns delay in ms if successful, or -2L if unreachable, rejected, or timeout.
     */
    suspend fun testPing(server: VpnServer, timeoutMs: Int = 3000): Long = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val res = kotlinx.coroutines.withTimeoutOrNull(timeoutMs.toLong()) {
                val address = InetAddress.getByName(server.host)
                val socketAddress = InetSocketAddress(address, server.port)

                Socket().use { rawSocket ->
                    rawSocket.tcpNoDelay = true
                    rawSocket.soTimeout = timeoutMs
                    rawSocket.connect(socketAddress, timeoutMs)

                    val isAlive = when (server.security.lowercase()) {
                        "reality" -> probeReality(rawSocket, server, timeoutMs)
                        "tls" -> probeTls(rawSocket, server, timeoutMs)
                        else -> {
                            if (server.network.equals("ws", ignoreCase = true)) {
                                probeWebSocket(rawSocket, server, timeoutMs)
                            } else {
                                probeTcp(rawSocket, server, timeoutMs)
                            }
                        }
                    }

                    if (!isAlive) {
                        return@withTimeoutOrNull -2L
                    }

                    val endTime = System.currentTimeMillis()
                    val delta = endTime - startTime
                    if (delta <= 0) 1L else delta
                }
            } ?: -2L

            if (res > 0) {
                LogManager.d("PingTest", "Ping to '${server.name}' (${server.host}:${server.port}): ${res}ms")
            } else {
                LogManager.w("PingTest", "Ping to '${server.name}' (${server.host}:${server.port}) OFFLINE/TIMEOUT")
            }
            res
        } catch (e: Exception) {
            LogManager.w("PingTest", "Ping to '${server.name}' (${server.host}:${server.port}) error: ${e.message}")
            -2L
        }
    }

    private fun probeTls(rawSocket: Socket, server: VpnServer, timeoutMs: Int): Boolean {
        return try {
            val sslContext = SSLContext.getInstance("TLS")
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            val sni = if (server.sni.isNotEmpty()) server.sni else server.host
            val sslSocket = sslContext.socketFactory.createSocket(
                rawSocket,
                server.host,
                server.port,
                true
            ) as SSLSocket

            val sslParams = sslSocket.sslParameters
            sslParams.serverNames = listOf(javax.net.ssl.SNIHostName(sni))
            sslSocket.sslParameters = sslParams
            sslSocket.soTimeout = timeoutMs

            if (server.network.equals("ws", ignoreCase = true)) {
                sslSocket.startHandshake()
                probeWebSocket(sslSocket, server, timeoutMs)
            } else if (server.network.equals("xhttp", ignoreCase = true) || server.network.equals("splithttp", ignoreCase = true) || server.network.equals("http", ignoreCase = true) || server.network.equals("h2", ignoreCase = true) || server.network.equals("grpc", ignoreCase = true)) {
                sslSocket.startHandshake()
                probeHttp(sslSocket, server, timeoutMs)
            } else {
                sslSocket.startHandshake()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun probeHttp(socket: Socket, server: VpnServer, timeoutMs: Int): Boolean {
        return try {
            val path = if (server.path.isNotEmpty()) server.path else "/"
            val sni = if (server.sni.isNotEmpty()) server.sni else server.host
            val req = "POST $path HTTP/1.1\r\nHost: $sni\r\nUser-Agent: Mozilla/5.0\r\nContent-Type: application/octet-stream\r\nConnection: keep-alive\r\n\r\n"

            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            socket.soTimeout = timeoutMs

            out.write(req.toByteArray(Charsets.UTF_8))
            out.flush()

            val respBuf = ByteArray(256)
            val bytesRead = input.read(respBuf)
            if (bytesRead > 0) {
                val respStr = String(respBuf, 0, bytesRead, Charsets.UTF_8)
                respStr.startsWith("HTTP/")
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun probeReality(rawSocket: Socket, server: VpnServer, timeoutMs: Int): Boolean {
        return try {
            val pbkBytes = com.example.vpn.RealityHelper.parseHexOrBase64(server.publicKey)
            val sidBytes = com.example.vpn.RealityHelper.parseHexOrBase64(server.shortId)
            val sni = if (server.sni.isNotEmpty()) server.sni else server.host
            val alpn = if (server.alpn.isNotEmpty()) server.alpn else "h2,http/1.1"

            if (pbkBytes.isNotEmpty()) {
                val (clientHelloPacket, _) = com.example.vpn.RealityHelper.buildClientHello(
                    sni, pbkBytes, sidBytes, alpn
                )

                val out = rawSocket.getOutputStream()
                val input = rawSocket.getInputStream()
                rawSocket.soTimeout = timeoutMs

                out.write(clientHelloPacket)
                out.flush()

                val responseHeader = ByteArray(5)
                val read = input.read(responseHeader)
                if (read >= 5 && (responseHeader[0] == 0x16.toByte() || responseHeader[0] == 0x14.toByte() || responseHeader[0] == 0x17.toByte())) {
                    return true
                }
            }
            probeTls(rawSocket, server, timeoutMs)
        } catch (_: Exception) {
            probeTls(rawSocket, server, timeoutMs)
        }
    }

    private fun probeWebSocket(socket: Socket, server: VpnServer, timeoutMs: Int): Boolean {
        return try {
            val path = if (server.path.isNotEmpty()) server.path else "/"
            val sni = if (server.sni.isNotEmpty()) server.sni else server.host
            val req = "GET $path HTTP/1.1\r\nHost: $sni\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"

            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            socket.soTimeout = timeoutMs

            out.write(req.toByteArray(Charsets.UTF_8))
            out.flush()

            val respBuf = ByteArray(256)
            val bytesRead = input.read(respBuf)
            if (bytesRead > 0) {
                val respStr = String(respBuf, 0, bytesRead, Charsets.UTF_8)
                respStr.startsWith("HTTP/")
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun probeTcp(socket: Socket, server: VpnServer, timeoutMs: Int): Boolean {
        return try {
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            out.write(byteArrayOf(0x00))
            out.flush()
            !socket.isClosed && socket.isConnected
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Measures latency for a batch of servers concurrently.
     * Returns map of serverId to latencyMs.
     */
    suspend fun testBatchPing(
        servers: List<VpnServer>,
        timeoutMs: Int = 3000,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<String, Long> = withContext(Dispatchers.IO) {
        val total = servers.size
        var completed = 0

        val deferreds = servers.map { server ->
            async {
                val ping = testPing(server, timeoutMs)
                synchronized(this@PingTester) {
                    completed++
                    onProgress(completed, total)
                }
                server.id to ping
            }
        }

        deferreds.awaitAll().toMap()
    }
}

