package com.example.util

import com.example.data.model.VpnProtocol
import com.example.data.model.VpnServer
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigGenerator {

    private fun findFreePort(defaultPort: Int): Int {
        return try {
            java.net.ServerSocket(defaultPort).use { socket ->
                socket.localPort
            }
        } catch (_: Exception) {
            try {
                java.net.ServerSocket(0).use { socket ->
                    socket.localPort
                }
            } catch (_: Exception) {
                defaultPort
            }
        }
    }

    fun generateConfigJson(server: VpnServer, socksPort: Int = 10808, httpPort: Int = 10809): String {
        val actualSocksPort = findFreePort(socksPort)
        val actualHttpPort = findFreePort(httpPort)
        val root = JSONObject()

        // 1. Log section
        val log = JSONObject().apply {
            put("loglevel", "info")
        }
        root.put("log", log)

        // 2. Inbounds (SOCKS5 & HTTP local proxies)
        val inbounds = JSONArray()
        val socksIn = JSONObject().apply {
            put("port", actualSocksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            put("tag", "socks-in")
        }
        inbounds.put(socksIn)

        val httpIn = JSONObject().apply {
            put("port", actualHttpPort)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("settings", JSONObject())
            put("tag", "http-in")
        }
        inbounds.put(httpIn)
        root.put("inbounds", inbounds)

        // 3. Outbounds
        val outbounds = JSONArray()

        val mainProxy = JSONObject()
        mainProxy.put("tag", "proxy")

        when (server.protocol) {
            VpnProtocol.VLESS -> {
                mainProxy.put("protocol", "vless")
                val user = JSONObject().apply {
                    put("id", server.uuid)
                    put("encryption", "none")
                    if (server.flow.isNotEmpty()) {
                        put("flow", server.flow)
                    }
                }
                val node = JSONObject().apply {
                    put("address", server.host)
                    put("port", server.port)
                    put("users", JSONArray().apply { put(user) })
                }
                mainProxy.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply { put(node) })
                })
            }
            VpnProtocol.VMESS -> {
                mainProxy.put("protocol", "vmess")
                val user = JSONObject().apply {
                    put("id", server.uuid)
                    put("security", "auto")
                    put("alterId", server.alterId)
                }
                val node = JSONObject().apply {
                    put("address", server.host)
                    put("port", server.port)
                    put("users", JSONArray().apply { put(user) })
                }
                mainProxy.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply { put(node) })
                })
            }
            VpnProtocol.TROJAN -> {
                mainProxy.put("protocol", "trojan")
                val node = JSONObject().apply {
                    put("address", server.host)
                    put("port", server.port)
                    put("password", server.uuid)
                }
                mainProxy.put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply { put(node) })
                })
            }
            VpnProtocol.SHADOWSOCKS -> {
                mainProxy.put("protocol", "shadowsocks")
                val node = JSONObject().apply {
                    put("address", server.host)
                    put("port", server.port)
                    put("method", if (server.security.isNotEmpty() && server.security != "none") server.security else "aes-256-gcm")
                    put("password", server.uuid)
                }
                mainProxy.put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply { put(node) })
                })
            }
            else -> {
                // Default to VLESS
                mainProxy.put("protocol", "vless")
                val user = JSONObject().apply {
                    put("id", server.uuid)
                    put("encryption", "none")
                }
                val node = JSONObject().apply {
                    put("address", server.host)
                    put("port", server.port)
                    put("users", JSONArray().apply { put(user) })
                }
                mainProxy.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply { put(node) })
                })
            }
        }

        // StreamSettings
        val streamSettings = JSONObject()
        val net = server.network.lowercase().ifEmpty { "tcp" }
        val sec = if (server.publicKey.isNotEmpty() || server.security.equals("reality", ignoreCase = true)) {
            "reality"
        } else if (server.security.equals("tls", ignoreCase = true) || server.port == 443) {
            "tls"
        } else {
            "none"
        }

        streamSettings.put("network", net)
        streamSettings.put("security", sec)

        if (sec == "tls") {
            val tlsSettings = JSONObject().apply {
                val sni = server.sni.ifEmpty { server.host }
                put("serverName", sni)
                put("allowInsecure", true)
                put("fingerprint", server.fingerprint.ifEmpty { "chrome" })
                if (server.alpn.isNotEmpty()) {
                    val alpnArray = JSONArray()
                    server.alpn.split(",").forEach { alpnArray.put(it.trim()) }
                    put("alpn", alpnArray)
                }
            }
            streamSettings.put("tlsSettings", tlsSettings)
        } else if (sec == "reality") {
            val realitySettings = JSONObject().apply {
                val sni = server.sni.ifEmpty { server.host }
                put("serverName", sni)
                put("publicKey", server.publicKey)
                put("shortId", server.shortId)
                put("fingerprint", server.fingerprint.ifEmpty { "chrome" })
                put("spiderX", "")
            }
            streamSettings.put("realitySettings", realitySettings)
        }

        when (net) {
            "ws" -> {
                val wsSettings = JSONObject().apply {
                    val path = if (server.path.isNotEmpty()) {
                        if (server.path.startsWith("/")) server.path else "/${server.path}"
                    } else "/"
                    put("path", path)
                    val headers = JSONObject().apply {
                        put("Host", server.sni.ifEmpty { server.host })
                    }
                    put("headers", headers)
                }
                streamSettings.put("wsSettings", wsSettings)
            }
            "grpc" -> {
                val grpcSettings = JSONObject().apply {
                    val svc = server.serviceName.ifEmpty { server.path }
                    put("serviceName", svc)
                    put("multiMode", false)
                }
                streamSettings.put("grpcSettings", grpcSettings)
            }
            "xhttp", "splithttp" -> {
                val xhttpSettings = JSONObject().apply {
                    val path = if (server.path.isNotEmpty()) {
                        if (server.path.startsWith("/")) server.path else "/${server.path}"
                    } else "/"
                    put("path", path)
                    put("host", server.sni.ifEmpty { server.host })
                }
                streamSettings.put("xhttpSettings", xhttpSettings)
            }
        }

        mainProxy.put("streamSettings", streamSettings)
        outbounds.put(mainProxy)

        // Direct outbound
        val directOutbound = JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        }
        outbounds.put(directOutbound)

        // Block outbound
        val blockOutbound = JSONObject().apply {
            put("protocol", "blackhole")
            put("tag", "block")
        }
        outbounds.put(blockOutbound)

        root.put("outbounds", outbounds)

        // 4. Routing
        val routing = JSONObject().apply {
            put("domainStrategy", "AsIs")
            val rules = JSONArray()

            val mainRule = JSONObject().apply {
                put("type", "field")
                put("outboundTag", "proxy")
                put("network", "tcp,udp")
                put("port", "0-65535")
            }
            rules.put(mainRule)

            put("rules", rules)
        }
        root.put("routing", routing)

        return root.toString(2)
    }
}
