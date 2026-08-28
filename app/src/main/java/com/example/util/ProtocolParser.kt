package com.example.util

import android.net.Uri
import android.util.Base64
import com.example.data.model.VpnProtocol
import com.example.data.model.VpnServer
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object ProtocolParser {

    fun parseLink(rawLink: String, subscriptionId: String = "manual", defaultGroup: String = "Default"): VpnServer? {
        val trimmed = rawLink.trim().removePrefix("\uFEFF")
        return when {
            trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed, subscriptionId, defaultGroup)
            trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed, subscriptionId, defaultGroup)
            trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed, subscriptionId, defaultGroup)
            trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed, subscriptionId, defaultGroup)
            trimmed.startsWith("socks://", ignoreCase = true) || trimmed.startsWith("socks5://", ignoreCase = true) -> parseSocks(trimmed, subscriptionId, defaultGroup)
            trimmed.startsWith("hy2://", ignoreCase = true) || trimmed.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(trimmed, subscriptionId, defaultGroup)
            else -> null
        }
    }

    private fun parseVless(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            val cleanLink = link.trim()
            val fragment = if (cleanLink.contains("#")) cleanLink.substringAfter("#") else ""
            val linkNoFragment = if (cleanLink.contains("#")) cleanLink.substringBefore("#") else cleanLink

            val name = if (fragment.isNotEmpty()) {
                try { URLDecoder.decode(fragment, "UTF-8") } catch (_: Exception) { fragment }
            } else "VLESS Server"

            val uri = Uri.parse(linkNoFragment)
            val userInfo = uri.userInfo ?: ""
            val uuid = userInfo.ifEmpty { "00000000-0000-0000-0000-000000000000" }
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443

            val security = uri.getQueryParameter("security") ?: uri.getQueryParameter("encryption") ?: "tls"
            val network = uri.getQueryParameter("type") ?: uri.getQueryParameter("network") ?: uri.getQueryParameter("headerType") ?: "tcp"
            val rawPath = uri.getQueryParameter("path") ?: ""
            val path = try { URLDecoder.decode(rawPath, "UTF-8") } catch (_: Exception) { rawPath }
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host
            val publicKey = uri.getQueryParameter("pbk") ?: uri.getQueryParameter("publicKey") ?: ""
            val shortId = uri.getQueryParameter("sid") ?: uri.getQueryParameter("shortId") ?: ""
            val fingerprint = uri.getQueryParameter("fp") ?: uri.getQueryParameter("fingerprint") ?: "chrome"
            val flow = uri.getQueryParameter("flow") ?: ""
            val serviceName = uri.getQueryParameter("serviceName") ?: uri.getQueryParameter("authority") ?: path
            val alpn = uri.getQueryParameter("alpn") ?: "h2,http/1.1"

            val group = extractGroupFromName(name, defaultGroup)

            VpnServer(
                id = generateId(cleanLink, host, port, uuid),
                subscriptionId = subscriptionId,
                name = name,
                protocol = VpnProtocol.VLESS,
                host = host,
                port = port,
                uuid = uuid,
                security = security,
                network = network,
                path = path,
                sni = sni,
                publicKey = publicKey,
                shortId = shortId,
                fingerprint = fingerprint,
                flow = flow,
                serviceName = serviceName,
                alpn = alpn,
                groupName = group,
                rawLink = cleanLink
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVmess(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            val cleanLink = link.trim()
            val base64Data = cleanLink.substringAfter("vmess://").substringBefore("#").trim()
            val jsonString = decodeBase64Safe(base64Data)
            val json = JSONObject(jsonString)

            val name = json.optString("ps", "VMess Server")
            val host = json.optString("add", "")
            if (host.isEmpty()) return null

            val port = json.optInt("port", 443)
            val uuid = json.optString("id", "")
            val alterId = json.optInt("aid", 0)
            val network = json.optString("net", "tcp")
            val security = if (json.optString("tls").isNotEmpty() || json.optString("security") == "tls") "tls" else "none"
            val path = json.optString("path", "")
            val sni = json.optString("sni", json.optString("host", host))

            val group = extractGroupFromName(name, defaultGroup)

            VpnServer(
                id = generateId(cleanLink, host, port, uuid),
                subscriptionId = subscriptionId,
                name = name,
                protocol = VpnProtocol.VMESS,
                host = host,
                port = port,
                uuid = uuid,
                alterId = alterId,
                security = security,
                network = network,
                path = path,
                sni = sni,
                groupName = group,
                rawLink = cleanLink
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTrojan(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            val cleanLink = link.trim()
            val fragment = if (cleanLink.contains("#")) cleanLink.substringAfter("#") else ""
            val linkNoFragment = if (cleanLink.contains("#")) cleanLink.substringBefore("#") else cleanLink

            val name = if (fragment.isNotEmpty()) {
                try { URLDecoder.decode(fragment, "UTF-8") } catch (_: Exception) { fragment }
            } else "Trojan Server"

            val uri = Uri.parse(linkNoFragment)
            val password = uri.userInfo ?: ""
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443

            val security = uri.getQueryParameter("security") ?: "tls"
            val network = uri.getQueryParameter("type") ?: uri.getQueryParameter("network") ?: "tcp"
            val rawPath = uri.getQueryParameter("path") ?: ""
            val path = try { URLDecoder.decode(rawPath, "UTF-8") } catch (_: Exception) { rawPath }
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host

            val group = extractGroupFromName(name, defaultGroup)

            VpnServer(
                id = generateId(cleanLink, host, port, password),
                subscriptionId = subscriptionId,
                name = name,
                protocol = VpnProtocol.TROJAN,
                host = host,
                port = port,
                uuid = password,
                security = security,
                network = network,
                path = path,
                sni = sni,
                groupName = group,
                rawLink = cleanLink
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseShadowsocks(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            val cleanLink = link.trim()
            val raw = cleanLink.substringAfter("ss://")
            val fragment = if (raw.contains("#")) raw.substringAfter("#") else ""
            val linkBody = if (raw.contains("#")) raw.substringBefore("#") else raw
            val name = if (fragment.isNotEmpty()) {
                try { URLDecoder.decode(fragment, "UTF-8") } catch (_: Exception) { fragment }
            } else "Shadowsocks"

            var host = ""
            var port = 8388
            var uuidPassword = ""

            if (linkBody.contains("@")) {
                val userPart = linkBody.substringBefore("@")
                val serverPart = linkBody.substringAfter("@")

                val decodedUser = decodeBase64Safe(userPart)
                uuidPassword = if (decodedUser.contains(":")) decodedUser else userPart

                val hostPort = serverPart.substringBefore("?")
                host = hostPort.substringBefore(":")
                port = hostPort.substringAfter(":", "8388").toIntOrNull() ?: 8388
            } else {
                val decoded = decodeBase64Safe(linkBody)
                if (decoded.contains("@")) {
                    val userInfo = decoded.substringBefore("@")
                    val hostPort = decoded.substringAfter("@").substringBefore("?")
                    uuidPassword = userInfo
                    host = hostPort.substringBefore(":")
                    port = hostPort.substringAfter(":", "8388").toIntOrNull() ?: 8388
                }
            }

            if (host.isEmpty()) return null
            val group = extractGroupFromName(name, defaultGroup)

            VpnServer(
                id = generateId(cleanLink, host, port, uuidPassword),
                subscriptionId = subscriptionId,
                name = name,
                protocol = VpnProtocol.SHADOWSOCKS,
                host = host,
                port = port,
                uuid = uuidPassword,
                security = "none",
                network = "tcp",
                groupName = group,
                rawLink = cleanLink
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSocks(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            val cleanLink = link.trim()
            val fragment = if (cleanLink.contains("#")) cleanLink.substringAfter("#") else ""
            val linkNoFragment = if (cleanLink.contains("#")) cleanLink.substringBefore("#") else cleanLink
            val name = if (fragment.isNotEmpty()) {
                try { URLDecoder.decode(fragment, "UTF-8") } catch (_: Exception) { fragment }
            } else "SOCKS5 Server"

            val uri = Uri.parse(linkNoFragment)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 1080
            val userInfo = uri.userInfo ?: ""
            val group = extractGroupFromName(name, defaultGroup)

            VpnServer(
                id = generateId(cleanLink, host, port, userInfo),
                subscriptionId = subscriptionId,
                name = name,
                protocol = VpnProtocol.SOCKS,
                host = host,
                port = port,
                uuid = userInfo,
                security = "none",
                network = "tcp",
                groupName = group,
                rawLink = cleanLink
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseHysteria2(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            val cleanLink = link.trim()
            val fragment = if (cleanLink.contains("#")) cleanLink.substringAfter("#") else ""
            val linkNoFragment = if (cleanLink.contains("#")) cleanLink.substringBefore("#") else cleanLink
            val name = if (fragment.isNotEmpty()) {
                try { URLDecoder.decode(fragment, "UTF-8") } catch (_: Exception) { fragment }
            } else "Hysteria2 Server"

            val uri = Uri.parse(linkNoFragment)
            val auth = uri.userInfo ?: ""
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val sni = uri.getQueryParameter("sni") ?: host

            val group = extractGroupFromName(name, defaultGroup)

            VpnServer(
                id = generateId(cleanLink, host, port, auth),
                subscriptionId = subscriptionId,
                name = name,
                protocol = VpnProtocol.HYSTERIA2,
                host = host,
                port = port,
                uuid = auth,
                security = "tls",
                network = "udp",
                sni = sni,
                groupName = group,
                rawLink = cleanLink
            )
        } catch (e: Exception) {
            null
        }
    }

    fun sanitizeAndDeduplicate(servers: List<VpnServer>): List<VpnServer> {
        val result = mutableListOf<VpnServer>()
        val seenSignatures = mutableSetOf<String>()

        val infoKeywords = listOf(
            "истекает", "срок", "баланс", "трафик", "инфо", "подписка", "канал", "сайт",
            "купить", "поддержка", "renew", "expire", "traffic", "notice", "telegram",
            "t.me", "http://", "https://", "remaining", "limit", "website", "version",
            "update", "skachat", "настройка", "инструкция", "support", "buy", "news", "новости"
        )

        for (server in servers) {
            val lowerName = server.name.lowercase()
            val lowerHost = server.host.lowercase()
            val lowerPath = server.path.lowercase()

            // Skip invalid loopback or invalid port hosts
            if (lowerHost in listOf("127.0.0.1", "0.0.0.0", "localhost", "::1")) continue
            if (server.port <= 0 || server.port > 65535) continue

            // Skip info / non-functional notice nodes
            var isInfoNode = false
            for (kw in infoKeywords) {
                if (lowerName.contains(kw) || lowerHost.contains(kw) || lowerPath.contains(kw)) {
                    isInfoNode = true
                    break
                }
            }
            if (isInfoNode) continue

            // Clean up name formatting
            var cleanName = server.name
                .replace(Regex("\\[(VLESS|VMESS|TROJAN|SS|SOCKS|HY2|HYSTERIA2|V2RAY|XRAY)\\]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^\\[.*?\\]"), "")
                .trim()
                .removePrefix("-")
                .removePrefix(":")
                .trim()

            if (cleanName.isBlank()) {
                cleanName = "${server.protocol.name} ${server.host}"
            }

            val group = extractGroupFromName(cleanName, server.groupName)
            val formattedServer = server.copy(
                name = cleanName,
                groupName = group
            )

            // Deduplicate by unique server configuration signature
            val sig = "${formattedServer.protocol}_${formattedServer.host.lowercase()}_${formattedServer.port}_${formattedServer.uuid}_${formattedServer.security}_${formattedServer.network}_${formattedServer.path}_${formattedServer.sni}_${formattedServer.publicKey}"
            if (seenSignatures.add(sig)) {
                result.add(formattedServer)
            }
        }
        return result
    }

    fun parseSubscriptionContent(content: String, subscriptionId: String, groupName: String): List<VpnServer> {
        val cleanContent = content.trim().removePrefix("\uFEFF")
        if (cleanContent.isEmpty()) return emptyList()

        val parsedRaw = mutableListOf<VpnServer>()

        // 1. Direct JSON (Sing-box / Xray / V2Ray)
        val jsonServers = parseJsonConfig(cleanContent, subscriptionId, groupName)
        if (jsonServers.isNotEmpty()) {
            parsedRaw.addAll(jsonServers)
        } else {
            // 2. Base64 decoded content
            val decoded = decodeBase64Safe(cleanContent)
            if (decoded != cleanContent && decoded.isNotBlank()) {
                val decodedJson = parseJsonConfig(decoded, subscriptionId, groupName)
                if (decodedJson.isNotEmpty()) {
                    parsedRaw.addAll(decodedJson)
                }
            }

            // 3. Clash YAML format
            if (parsedRaw.isEmpty() && (cleanContent.contains("proxies:") || decoded.contains("proxies:"))) {
                val yamlServers = parseClashYaml(cleanContent, subscriptionId, groupName).ifEmpty {
                    parseClashYaml(decoded, subscriptionId, groupName)
                }
                if (yamlServers.isNotEmpty()) {
                    parsedRaw.addAll(yamlServers)
                }
            }

            // 4. Line-by-line URI parsing
            if (parsedRaw.isEmpty()) {
                val candidates = listOf(decoded, cleanContent)
                val seenIds = mutableSetOf<String>()

                for (candidate in candidates) {
                    candidate.lines().forEach { line ->
                        val trimmedLine = line.trim()
                        if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                            var server = parseLink(trimmedLine, subscriptionId, groupName)
                            if (server == null && trimmedLine.length > 25 && !trimmedLine.contains("://")) {
                                val decodedLine = decodeBase64Safe(trimmedLine)
                                if (decodedLine != trimmedLine) {
                                    server = parseLink(decodedLine, subscriptionId, groupName)
                                }
                            }
                            if (server != null && seenIds.add(server.id)) {
                                parsedRaw.add(server)
                            }
                        }
                    }
                    if (parsedRaw.isNotEmpty()) break
                }
            }
        }

        return sanitizeAndDeduplicate(parsedRaw)
    }

    fun parseClashYaml(content: String, subscriptionId: String, defaultGroup: String): List<VpnServer> {
        val servers = mutableListOf<VpnServer>()
        try {
            val lines = content.lines()
            var insideProxies = false
            val currentMap = mutableMapOf<String, String>()

            fun flushCurrent() {
                if (currentMap.isNotEmpty()) {
                    val type = currentMap["type"]?.lowercase() ?: ""
                    val name = currentMap["name"] ?: "Clash Node"
                    val server = currentMap["server"] ?: ""
                    val port = currentMap["port"]?.toIntOrNull() ?: 443
                    val uuid = currentMap["uuid"] ?: currentMap["password"] ?: ""
                    val tls = currentMap["tls"] == "true" || currentMap["security"] == "tls"
                    val sni = currentMap["servername"] ?: currentMap["sni"] ?: server
                    val network = currentMap["network"] ?: currentMap["type"] ?: "tcp"
                    val path = currentMap["path"] ?: ""

                    val protocol = when (type) {
                        "vless" -> VpnProtocol.VLESS
                        "vmess" -> VpnProtocol.VMESS
                        "trojan" -> VpnProtocol.TROJAN
                        "ss", "shadowsocks" -> VpnProtocol.SHADOWSOCKS
                        "hysteria2", "hy2" -> VpnProtocol.HYSTERIA2
                        "socks5", "socks" -> VpnProtocol.SOCKS
                        else -> null
                    }

                    if (protocol != null && server.isNotEmpty()) {
                        val group = extractGroupFromName(name, defaultGroup)
                        val rawLink = buildV2RayUri(protocol, uuid, server, port, network, if (tls) "tls" else "none", path, sni, name)
                        servers.add(
                            VpnServer(
                                id = generateId(rawLink, server, port, uuid),
                                subscriptionId = subscriptionId,
                                name = name,
                                protocol = protocol,
                                host = server,
                                port = port,
                                uuid = uuid,
                                security = if (tls) "tls" else "none",
                                network = network,
                                path = path,
                                sni = sni,
                                groupName = group,
                                rawLink = rawLink
                            )
                        )
                    }
                    currentMap.clear()
                }
            }

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed == "proxies:") {
                    insideProxies = true
                    continue
                }
                if (insideProxies) {
                    if (trimmed.startsWith("- name:") || trimmed.startsWith("- { name:")) {
                        flushCurrent()
                    }
                    if (trimmed.startsWith("proxy-groups:") || trimmed.startsWith("rules:")) {
                        flushCurrent()
                        insideProxies = false
                        break
                    }
                    val parts = trimmed.removePrefix("- ").split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().lowercase().removeSurrounding("\"").removeSurrounding("'")
                        val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                        currentMap[key] = value
                    }
                }
            }
            flushCurrent()
        } catch (_: Exception) {}
        return servers
    }

    fun isHtmlOrErrorBody(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        val lower = trimmed.lowercase()
        return lower.contains("<html") || lower.contains("<!doctype html") ||
                lower.contains("приложение не поддерживается") || lower.contains("app is not supported")
    }

    fun parseJsonConfig(content: String, subscriptionId: String, defaultGroup: String): List<VpnServer> {
        val servers = mutableListOf<VpnServer>()
        try {
            val jsonStr = content.trim()
            if (jsonStr.startsWith("{")) {
                val jsonObj = JSONObject(jsonStr)
                val globalRemark = jsonObj.optString("remarks", jsonObj.optString("name", ""))

                val outbounds = jsonObj.optJSONArray("outbounds")
                if (outbounds != null) {
                    for (i in 0 until outbounds.length()) {
                        val ob = outbounds.optJSONObject(i) ?: continue
                        val server = parseOutboundJson(ob, defaultGroup, subscriptionId, globalRemark)
                        if (server != null) {
                            servers.add(server)
                        }
                    }
                } else {
                    val server = parseOutboundJson(jsonObj, defaultGroup, subscriptionId, globalRemark)
                    if (server != null) servers.add(server)
                }
            } else if (jsonStr.startsWith("[")) {
                val jsonArr = org.json.JSONArray(jsonStr)
                for (i in 0 until jsonArr.length()) {
                    val item = jsonArr.optJSONObject(i) ?: continue
                    val globalRemark = item.optString("remarks", item.optString("name", ""))
                    val outbounds = item.optJSONArray("outbounds")
                    if (outbounds != null) {
                        for (j in 0 until outbounds.length()) {
                            val ob = outbounds.optJSONObject(j) ?: continue
                            val server = parseOutboundJson(ob, defaultGroup, subscriptionId, globalRemark)
                            if (server != null) {
                                servers.add(server)
                            }
                        }
                    } else {
                        val server = parseOutboundJson(item, defaultGroup, subscriptionId, globalRemark)
                        if (server != null) {
                            servers.add(server)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return servers
    }

    private fun parseOutboundJson(
        ob: JSONObject,
        defaultGroup: String,
        subscriptionId: String,
        globalRemark: String = ""
    ): VpnServer? {
        val protocolStr = ob.optString("protocol", ob.optString("type", "")).lowercase()
        if (protocolStr in listOf("direct", "freedom", "blackhole", "block", "dns", "")) return null

        val protocol = when (protocolStr) {
            "vless" -> VpnProtocol.VLESS
            "vmess" -> VpnProtocol.VMESS
            "trojan" -> VpnProtocol.TROJAN
            "shadowsocks", "ss" -> VpnProtocol.SHADOWSOCKS
            "socks", "socks5" -> VpnProtocol.SOCKS
            "hysteria2", "hy2", "hysteria" -> VpnProtocol.HYSTERIA2
            else -> return null
        }

        var host = ob.optString("server", ob.optString("address", ""))
        var port = ob.optInt("server_port", ob.optInt("port", 443))
        var uuid = ob.optString("uuid", ob.optString("password", ob.optString("auth", "")))

        val settings = ob.optJSONObject("settings")
        if (settings != null) {
            if (host.isEmpty()) host = settings.optString("address", settings.optString("server", ""))
            if (port == 443 || port == 0) port = settings.optInt("port", settings.optInt("server_port", 443))
            if (uuid.isEmpty()) uuid = settings.optString("auth", settings.optString("password", ""))

            val vnext = settings.optJSONArray("vnext")
            if (vnext != null && vnext.length() > 0) {
                val target = vnext.getJSONObject(0)
                if (host.isEmpty()) host = target.optString("address", "")
                if (port == 443 || port == 0) port = target.optInt("port", 443)
                val users = target.optJSONArray("users")
                if (users != null && users.length() > 0) {
                    val u = users.getJSONObject(0)
                    if (uuid.isEmpty()) uuid = u.optString("id", u.optString("uuid", u.optString("password", "")))
                }
            }
            val servers = settings.optJSONArray("servers")
            if (servers != null && servers.length() > 0) {
                val target = servers.getJSONObject(0)
                if (host.isEmpty()) host = target.optString("address", "")
                if (port == 443 || port == 0) port = target.optInt("port", 443)
                if (uuid.isEmpty()) uuid = target.optString("password", target.optString("id", ""))
            }
        }

        if (host.isEmpty()) return null

        var network = "tcp"
        var path = ""
        var security = "none"
        var sni = host
        var publicKey = ""
        var shortId = ""
        var fingerprint = "chrome"
        var flow = ob.optString("flow", "")
        var serviceName = ""
        var alpn = "h2,http/1.1"

        val streamSettings = ob.optJSONObject("streamSettings")
        if (streamSettings != null) {
            network = streamSettings.optString("network", "tcp")
            security = streamSettings.optString("security", "none")

            val wsSettings = streamSettings.optJSONObject("wsSettings")
            if (wsSettings != null) {
                path = wsSettings.optString("path", "")
                val wsHost = wsSettings.optString("host", "")
                if (wsHost.isNotEmpty()) sni = wsHost

                val headers = wsSettings.optJSONObject("headers")
                if (headers != null) {
                    val hHost = headers.optString("host", headers.optString("Host", ""))
                    if (hHost.isNotEmpty()) sni = hHost
                }
            }

            val grpcSettings = streamSettings.optJSONObject("grpcSettings")
            if (grpcSettings != null) {
                serviceName = grpcSettings.optString("serviceName", grpcSettings.optString("authority", path))
                if (path.isEmpty()) path = serviceName
            }

            val tlsSettings = streamSettings.optJSONObject("tlsSettings")
            if (tlsSettings != null) {
                val sName = tlsSettings.optString("serverName", tlsSettings.optString("sni", ""))
                if (sName.isNotEmpty()) sni = sName
                fingerprint = tlsSettings.optString("fingerprint", tlsSettings.optString("fp", "chrome"))
                val alpnArr = tlsSettings.optJSONArray("alpn")
                if (alpnArr != null && alpnArr.length() > 0) {
                    val list = mutableListOf<String>()
                    for (k in 0 until alpnArr.length()) list.add(alpnArr.getString(k))
                    alpn = list.joinToString(",")
                }
            }

            val realitySettings = streamSettings.optJSONObject("realitySettings")
            if (realitySettings != null) {
                security = "reality"
                val sName = realitySettings.optString("serverName", realitySettings.optString("sni", ""))
                if (sName.isNotEmpty()) sni = sName
                publicKey = realitySettings.optString("publicKey", realitySettings.optString("pbk", ""))
                shortId = realitySettings.optString("shortId", realitySettings.optString("sid", ""))
                fingerprint = realitySettings.optString("fingerprint", realitySettings.optString("fp", "chrome"))
            }

            val hysteriaSettings = streamSettings.optJSONObject("hysteriaSettings") ?: streamSettings.optJSONObject("hy2Settings")
            if (hysteriaSettings != null) {
                if (uuid.isEmpty()) uuid = hysteriaSettings.optString("auth", hysteriaSettings.optString("password", ""))
            }
        }

        val transport = ob.optJSONObject("transport")
        if (transport != null) {
            network = transport.optString("type", network)
            path = transport.optString("path", path)
        }
        val tls = ob.optJSONObject("tls")
        if (tls != null) {
            if (tls.optBoolean("enabled", false)) security = "tls"
            sni = tls.optString("server_name", sni)
        }

        val tag = ob.optString("tag", ob.optString("remarks", ""))
        val name = when {
            globalRemark.isNotEmpty() && tag.isNotEmpty() -> {
                if (tag.equals("proxy", ignoreCase = true)) {
                    globalRemark
                } else if (tag.startsWith("proxy-", ignoreCase = true)) {
                    val num = tag.substringAfter("proxy-")
                    "$globalRemark #$num"
                } else {
                    "$globalRemark ($tag)"
                }
            }
            tag.isNotEmpty() -> tag
            globalRemark.isNotEmpty() -> globalRemark
            else -> "${protocol.name} $host"
        }

        val group = extractGroupFromName(name, defaultGroup)
        val rawLink = buildV2RayUri(protocol, uuid, host, port, network, security, path, sni, name, publicKey, shortId, fingerprint, flow, serviceName, alpn)

        return VpnServer(
            id = generateId(ob.toString(), host, port, uuid),
            subscriptionId = subscriptionId,
            name = name,
            protocol = protocol,
            host = host,
            port = port,
            uuid = uuid,
            security = security,
            network = network,
            path = path,
            sni = sni,
            publicKey = publicKey,
            shortId = shortId,
            fingerprint = fingerprint,
            flow = flow,
            serviceName = serviceName,
            alpn = alpn,
            groupName = group,
            rawLink = rawLink
        )
    }

    private fun buildV2RayUri(
        protocol: VpnProtocol,
        uuid: String,
        host: String,
        port: Int,
        network: String,
        security: String,
        path: String,
        sni: String,
        name: String,
        publicKey: String = "",
        shortId: String = "",
        fingerprint: String = "chrome",
        flow: String = "",
        serviceName: String = "",
        alpn: String = "h2,http/1.1"
    ): String {
        return try {
            val encPath = if (path.isNotEmpty()) java.net.URLEncoder.encode(path, "UTF-8") else ""
            val encSni = if (sni.isNotEmpty()) java.net.URLEncoder.encode(sni, "UTF-8") else ""
            val encName = java.net.URLEncoder.encode(name, "UTF-8")
            val encPbk = if (publicKey.isNotEmpty()) java.net.URLEncoder.encode(publicKey, "UTF-8") else ""
            val encSid = if (shortId.isNotEmpty()) java.net.URLEncoder.encode(shortId, "UTF-8") else ""
            val encFp = java.net.URLEncoder.encode(fingerprint, "UTF-8")
            val encFlow = java.net.URLEncoder.encode(flow, "UTF-8")
            val encService = if (serviceName.isNotEmpty()) java.net.URLEncoder.encode(serviceName, "UTF-8") else ""

            when (protocol) {
                VpnProtocol.VLESS -> "vless://$uuid@$host:$port?type=$network&security=$security&path=$encPath&sni=$encSni&pbk=$encPbk&sid=$encSid&fp=$encFp&flow=$encFlow&serviceName=$encService#$encName"
                VpnProtocol.VMESS -> {
                    val jsonObj = JSONObject().apply {
                        put("v", "2")
                        put("ps", name)
                        put("add", host)
                        put("port", port)
                        put("id", uuid)
                        put("net", network)
                        put("tls", security)
                        put("path", path)
                        put("sni", sni)
                    }
                    val base64 = Base64.encodeToString(jsonObj.toString().toByteArray(), Base64.NO_WRAP)
                    "vmess://$base64"
                }
                VpnProtocol.TROJAN -> "trojan://$uuid@$host:$port?type=$network&security=$security&path=$encPath&sni=$encSni#$encName"
                VpnProtocol.SHADOWSOCKS -> "ss://$uuid@$host:$port#$encName"
                else -> "vless://$uuid@$host:$port?type=$network&security=$security&path=$encPath&sni=$encSni#$encName"
            }
        } catch (e: Exception) {
            "vless://$uuid@$host:$port#$name"
        }
    }

    fun isUnsupportedPanelResponse(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return false
        }
        val lower = trimmed.lowercase()
        if (lower.contains("приложение не поддерживается") ||
            lower.contains("app is not supported") ||
            lower.contains("unsupported application") ||
            lower.contains("unsupported user-agent") ||
            lower.contains("client not supported")
        ) {
            return true
        }

        if ((lower.contains("<html") || lower.contains("<!doctype html")) &&
            !lower.contains("vless://") && !lower.contains("vmess://") && !lower.contains("trojan://") && !lower.contains("ss://")
        ) {
            return true
        }

        return false
    }

    private fun extractGroupFromName(name: String, fallback: String): String {
        val upper = name.uppercase()
        return when {
            upper.contains("DE") || upper.contains("GERMANY") || upper.contains("ГЕРМАНИЯ") || upper.contains("FRANKFURT") -> "🇩🇪 Germany"
            upper.contains("NL") || upper.contains("NETHERLANDS") || upper.contains("НИДЕРЛАНДЫ") || upper.contains("AMSTERDAM") -> "🇳🇱 Netherlands"
            upper.contains("US") || upper.contains("USA") || upper.contains("США") || upper.contains("UNITED STATES") -> "🇺🇸 USA"
            upper.contains("FI") || upper.contains("FINLAND") || upper.contains("ФИНЛЯНДИЯ") || upper.contains("HELSINKI") -> "🇫🇮 Finland"
            upper.contains("SG") || upper.contains("SINGAPORE") || upper.contains("СИНГАПУР") -> "🇸🇬 Singapore"
            upper.contains("JP") || upper.contains("JAPAN") || upper.contains("ЯПОНИЯ") || upper.contains("TOKYO") -> "🇯🇵 Japan"
            upper.contains("GB") || upper.contains("UK") || upper.contains("БРИТАНИЯ") || upper.contains("LONDON") -> "🇬🇧 UK"
            upper.contains("FR") || upper.contains("FRANCE") || upper.contains("ФРАНЦИЯ") || upper.contains("PARIS") -> "🇫🇷 France"
            upper.contains("SE") || upper.contains("SWEDEN") || upper.contains("ШВЕЦИЯ") || upper.contains("STOCKHOLM") -> "🇸🇪 Sweden"
            upper.contains("PL") || upper.contains("POLAND") || upper.contains("ПОЛЬША") || upper.contains("WARSAW") -> "🇵🇱 Poland"
            upper.contains("TR") || upper.contains("TURKEY") || upper.contains("ТУРЦИЯ") || upper.contains("ISTANBUL") -> "🇹🇷 Turkey"
            upper.contains("KZ") || upper.contains("KAZAKHSTAN") || upper.contains("КАЗАХСТАН") -> "🇰🇿 Kazakhstan"
            upper.contains("AT") || upper.contains("AUSTRIA") || upper.contains("АВСТРИЯ") || upper.contains("VIENNA") -> "🇦🇹 Austria"
            upper.contains("CH") || upper.contains("SWITZERLAND") || upper.contains("ШВЕЙЦАРИЯ") || upper.contains("ZURICH") -> "🇨🇭 Switzerland"
            upper.contains("CA") || upper.contains("CANADA") || upper.contains("КАНАДА") || upper.contains("TORONTO") -> "🇨🇦 Canada"
            upper.contains("ES") || upper.contains("SPAIN") || upper.contains("ИСПАНИЯ") || upper.contains("MADRID") -> "🇪🇸 Spain"
            upper.contains("IT") || upper.contains("ITALY") || upper.contains("ИТАЛИЯ") || upper.contains("MILAN") -> "🇮🇹 Italy"
            fallback.isNotEmpty() && fallback != "Default" && fallback != "manual" -> fallback
            else -> "General"
        }
    }

    private fun generateId(raw: String, host: String, port: Int, uuid: String): String {
        return UUID.nameUUIDFromBytes("$raw-$host-$port-$uuid".toByteArray(StandardCharsets.UTF_8)).toString()
    }

    fun decodeBase64Safe(input: String): String {
        val clean = input.trim().removePrefix("\uFEFF").replace("\r", "").replace("\n", "").replace(" ", "")
        if (clean.isEmpty()) return input

        var normalized = clean.replace("-", "+").replace("_", "/")
        val padRemainder = normalized.length % 4
        if (padRemainder > 0) {
            normalized += "=".repeat(4 - padRemainder)
        }

        return try {
            val bytes = Base64.decode(normalized, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            try {
                val bytes = Base64.decode(clean, Base64.URL_SAFE)
                String(bytes, StandardCharsets.UTF_8)
            } catch (_: Exception) {
                input
            }
        }
    }

    fun getSampleServers(): List<VpnServer> {
        return emptyList()
    }
}

