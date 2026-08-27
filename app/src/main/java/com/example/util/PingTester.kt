package com.example.util

import com.example.data.model.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object PingTester {

    /**
     * Measures TCP connection latency to server host:port in milliseconds.
     * Returns delay in ms if successful, or -2L if timeout/connection failed.
     */
    suspend fun testPing(server: VpnServer, timeoutMs: Int = 3000): Long = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val res = kotlinx.coroutines.withTimeoutOrNull(timeoutMs.toLong()) {
                val address = java.net.InetAddress.getByName(server.host)
                val socketAddress = java.net.InetSocketAddress(address, server.port)
                java.net.Socket().use { socket ->
                    socket.connect(socketAddress, timeoutMs)
                }
                val endTime = System.currentTimeMillis()
                val delta = endTime - startTime
                if (delta <= 0) 1L else delta
            } ?: -2L
            if (res > 0) {
                LogManager.d("PingTest", "Ping to '${server.name}' (${server.host}:${server.port}): ${res}ms")
            } else {
                LogManager.w("PingTest", "Ping to '${server.name}' (${server.host}:${server.port}) TIMEOUT/FAILED")
            }
            res
        } catch (e: Exception) {
            LogManager.e("PingTest", "Ping to '${server.name}' (${server.host}:${server.port}) error: ${e.message}")
            -2L
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
