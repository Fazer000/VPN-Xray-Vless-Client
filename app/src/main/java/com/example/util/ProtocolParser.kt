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
        val trimmed = rawLink.trim()
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
            val uri = Uri.parse(link)
            val userInfo = uri.userInfo ?: ""
            val uuid = userInfo.ifEmpty { "00000000-0000-0000-0000-000000000000" }
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val fragment = uri.fragment ?: ""
            val name = if (fragment.isNotEmpty()) URLDecoder.decode(fragment, "UTF-8") else "VLESS $host"

            val security = uri.getQueryParameter("security") ?: "tls"
            val network = uri.getQueryParameter("type") ?: uri.getQueryParameter("network") ?: "tcp"
            val path = uri.getQueryParameter("path")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host

            val group = extractGroupFromName(name, defaultGroup)

            VpnServer(
                id = generateId(link, host, port, uuid),
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
                groupName = group,
                rawLink = link
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseVmess(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            val base64Data = link.substringAfter("vmess://").trim()
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
                id = generateId(link, host, port, uuid),
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
                rawLink = link
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseTrojan(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            val uri = Uri.parse(link)
            val password = uri.userInfo ?: ""
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val fragment = uri.fragment ?: ""
            val name = if (fragment.isNotEmpty()) URLDecoder.decode(fragment, "UTF-8") else "Trojan $host"

            val security = uri.getQueryParameter("security") ?: "tls"
            val network = uri.getQueryParameter("type") ?: uri.getQueryParameter("network") ?: "tcp"
            val path = uri.getQueryParameter("path")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host

            val group = extractGroupFromName(name, defaultGroup)

            VpnServer(
                id = generateId(link, host, port, password),
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
                rawLink = link
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseShadowsocks(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            // ss://base64(method:password@host:port)#remark or ss://base64(method:password)@host:port#remark
            val raw = link.substringAfter("ss://")
            val fragment = if (raw.contains("#")) raw.substringAfter("#") else ""
            val linkBody = if (raw.contains("#")) raw.substringBefore("#") else raw
            val name = if (fragment.isNotEmpty()) URLDecoder.decode(fragment, "UTF-8") else "Shadowsocks"

            var host = ""
            var port = 8388
            var uuidPassword = ""

            if (linkBody.contains("@")) {
                val userPart = linkBody.substringBefore("@")
                val serverPart = linkBody.substringAfter("@")

                val decodedUser = decodeBase64Safe(userPart)
                uuidPassword = decodedUser

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
                id = generateId(link, host, port, uuidPassword),
                subscriptionId = subscriptionId,
                name = name,
                protocol = VpnProtocol.SHADOWSOCKS,
                host = host,
                port = port,
                uuid = uuidPassword,
                security = "none",
                network = "tcp",
                groupName = group,
                rawLink = link
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseSocks(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            val uri = Uri.parse(link)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 1080
            val userInfo = uri.userInfo ?: ""
            val fragment = uri.fragment ?: ""
            val name = if (fragment.isNotEmpty()) URLDecoder.decode(fragment, "UTF-8") else "SOCKS5 $host"
            val group = extractGroupFromName(name, defaultGroup)

            VpnServer(
                id = generateId(link, host, port, userInfo),
                subscriptionId = subscriptionId,
                name = name,
                protocol = VpnProtocol.SOCKS,
                host = host,
                port = port,
                uuid = userInfo,
                security = "none",
                network = "tcp",
                groupName = group,
                rawLink = link
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseHysteria2(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            val uri = Uri.parse(link)
            val auth = uri.userInfo ?: ""
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val fragment = uri.fragment ?: ""
            val name = if (fragment.isNotEmpty()) URLDecoder.decode(fragment, "UTF-8") else "Hysteria2 $host"
            val sni = uri.getQueryParameter("sni") ?: host

            val group = extractGroupFromName(name, defaultGroup)

            VpnServer(
                id = generateId(link, host, port, auth),
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
                rawLink = link
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun parseSubscriptionContent(content: String, subscriptionId: String, groupName: String): List<VpnServer> {
        val trimmedContent = content.trim()

        // Detect if response is HTML or panel error message
        if (isUnsupportedPanelResponse(trimmedContent)) {
            throw IllegalArgumentException("Subscription panel error: Unsupported application or access restricted.")
        }

        // 1. Try parsing direct JSON
        val directJsonServers = parseJsonConfig(trimmedContent, subscriptionId, groupName)
        if (directJsonServers.isNotEmpty()) {
            return directJsonServers
        }

        // 2. Try parsing decoded base64 as JSON
        val decoded = decodeBase64Safe(trimmedContent)
        val decodedJsonServers = parseJsonConfig(decoded, subscriptionId, groupName)
        if (decodedJsonServers.isNotEmpty()) {
            return decodedJsonServers
        }

        // 3. Fallback to line-by-line URI parsing
        val containsProtocols = { str: String ->
            str.contains("vless://", ignoreCase = true) ||
            str.contains("vmess://", ignoreCase = true) ||
            str.contains("trojan://", ignoreCase = true) ||
            str.contains("ss://", ignoreCase = true) ||
            str.contains("socks://", ignoreCase = true) ||
            str.contains("hy2://", ignoreCase = true) ||
            str.contains("hysteria2://", ignoreCase = true)
        }

        val targetContent = if (containsProtocols(decoded)) decoded else trimmedContent

        val rawLines = mutableListOf<String>()
        targetContent.lines().forEach { line ->
            val lineClean = line.trim()
            if (lineClean.isNotEmpty() && !lineClean.startsWith("#")) {
                rawLines.add(lineClean)
            }
        }

        val servers = mutableListOf<VpnServer>()
        for (line in rawLines) {
            val server = parseLink(line, subscriptionId, groupName)
            if (server != null) {
                servers.add(server)
            }
        }
        return servers
    }

    fun parseJsonConfig(content: String, subscriptionId: String, defaultGroup: String): List<VpnServer> {
        val servers = mutableListOf<VpnServer>()
        try {
            val jsonStr = content.trim()
            if (jsonStr.startsWith("{")) {
                val jsonObj = JSONObject(jsonStr)
                val globalRemark = jsonObj.optString("remarks", "")

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
                    val ob = jsonArr.optJSONObject(i) ?: continue
                    val server = parseOutboundJson(ob, defaultGroup, subscriptionId)
                    if (server != null) {
                        servers.add(server)
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
            "hysteria2", "hy2" -> VpnProtocol.HYSTERIA2
            else -> return null
        }

        var host = ob.optString("server", ob.optString("address", ""))
        var port = ob.optInt("server_port", ob.optInt("port", 443))
        var uuid = ob.optString("uuid", ob.optString("password", ""))

        val settings = ob.optJSONObject("settings")
        if (settings != null) {
            val vnext = settings.optJSONArray("vnext")
            if (vnext != null && vnext.length() > 0) {
                val target = vnext.getJSONObject(0)
                if (host.isEmpty()) host = target.optString("address", "")
                if (port == 443) port = target.optInt("port", 443)
                val users = target.optJSONArray("users")
                if (users != null && users.length() > 0) {
                    val u = users.getJSONObject(0)
                    if (uuid.isEmpty()) uuid = u.optString("id", u.optString("uuid", ""))
                }
            }
            val servers = settings.optJSONArray("servers")
            if (servers != null && servers.length() > 0) {
                val target = servers.getJSONObject(0)
                if (host.isEmpty()) host = target.optString("address", "")
                if (port == 443) port = target.optInt("port", 443)
                if (uuid.isEmpty()) uuid = target.optString("password", target.optString("id", ""))
            }
        }

        if (host.isEmpty()) return null

        var network = "tcp"
        var path = ""
        var security = "none"
        var sni = host

        val streamSettings = ob.optJSONObject("streamSettings")
        if (streamSettings != null) {
            network = streamSettings.optString("network", "tcp")
            security = streamSettings.optString("security", "none")

            val wsSettings = streamSettings.optJSONObject("wsSettings")
            if (wsSettings != null) {
                path = wsSettings.optString("path", "")
                val headers = wsSettings.optJSONObject("headers")
                if (headers != null && headers.has("host")) {
                    sni = headers.optString("host", sni)
                }
            }

            val grpcSettings = streamSettings.optJSONObject("grpcSettings")
            if (grpcSettings != null) {
                path = grpcSettings.optString("serviceName", "")
            }

            val tlsSettings = streamSettings.optJSONObject("tlsSettings")
            if (tlsSettings != null) {
                sni = tlsSettings.optString("serverName", sni)
            }

            val realitySettings = streamSettings.optJSONObject("realitySettings")
            if (realitySettings != null) {
                security = "reality"
                sni = realitySettings.optString("serverName", sni)
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
            globalRemark.isNotEmpty() && tag.isNotEmpty() -> "$globalRemark ($tag)"
            tag.isNotEmpty() -> tag
            globalRemark.isNotEmpty() -> globalRemark
            else -> "${protocol.name} $host"
        }

        val group = extractGroupFromName(name, defaultGroup)
        val rawLink = buildV2RayUri(protocol, uuid, host, port, network, security, path, sni, name)

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
        name: String
    ): String {
        return try {
            val encPath = if (path.isNotEmpty()) java.net.URLEncoder.encode(path, "UTF-8") else ""
            val encSni = if (sni.isNotEmpty()) java.net.URLEncoder.encode(sni, "UTF-8") else ""
            val encName = java.net.URLEncoder.encode(name, "UTF-8")

            when (protocol) {
                VpnProtocol.VLESS -> "vless://$uuid@$host:$port?type=$network&security=$security&path=$encPath&sni=$encSni#$encName"
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
        return when {
            name.contains("DE") || name.contains("Germany") || name.contains("Германия") -> "🇩🇪 Germany"
            name.contains("NL") || name.contains("Netherlands") || name.contains("Нидерланды") -> "🇳🇱 Netherlands"
            name.contains("US") || name.contains("USA") || name.contains("США") -> "🇺🇸 USA"
            name.contains("FI") || name.contains("Finland") || name.contains("Финляндия") -> "🇫🇮 Finland"
            name.contains("SG") || name.contains("Singapore") || name.contains("Сингапур") -> "🇸🇬 Singapore"
            name.contains("JP") || name.contains("Japan") || name.contains("Япония") -> "🇯🇵 Japan"
            name.contains("GB") || name.contains("UK") || name.contains("Британия") -> "🇬🇧 UK"
            fallback.isNotEmpty() -> fallback
            else -> "General"
        }
    }

    private fun generateId(raw: String, host: String, port: Int, uuid: String): String {
        return UUID.nameUUIDFromBytes("$raw-$host-$port-$uuid".toByteArray(StandardCharsets.UTF_8)).toString()
    }

    fun decodeBase64Safe(input: String): String {
        return try {
            val clean = input.trim().replace("\r", "").replace("\n", "").replace(" ", "")
            var padded = clean
            while (padded.length % 4 != 0) {
                padded += "="
            }
            val decodedBytes = Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE)
            String(decodedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            input
        }
    }

    fun getSampleServers(): List<VpnServer> {
        return emptyList()
    }
}

