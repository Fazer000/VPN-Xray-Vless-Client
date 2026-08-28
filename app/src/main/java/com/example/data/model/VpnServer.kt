package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vpn_servers")
data class VpnServer(
    @PrimaryKey val id: String,
    val subscriptionId: String = "manual",
    val name: String,
    val protocol: VpnProtocol,
    val host: String,
    val port: Int,
    val uuid: String,
    val security: String = "tls", // tls, reality, none
    val network: String = "tcp",  // tcp, ws, grpc
    val path: String = "",
    val sni: String = "",
    val alterId: Int = 0,         // for VMess
    val publicKey: String = "",   // REALITY pbk
    val shortId: String = "",     // REALITY sid
    val fingerprint: String = "chrome", // fp: chrome, firefox, safari
    val flow: String = "",        // flow: xtls-rprx-vision
    val serviceName: String = "", // for gRPC / WS
    val alpn: String = "h2,http/1.1",
    val groupName: String = "Default",
    val isPinned: Boolean = false,
    val latencyMs: Long = -1L,    // -1 = untested, -2 = error/timeout
    val lastPingTimestamp: Long = 0L,
    val rawLink: String = ""
)
