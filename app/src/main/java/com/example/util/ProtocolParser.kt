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

        val rawLines = mutableListOf<String>()
        val decoded = decodeBase64Safe(trimmedContent)

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

    fun isUnsupportedPanelResponse(content: String): Boolean {
        val lower = content.lowercase()
        if (lower.contains("приложение не поддерживается") ||
            lower.contains("app is not supported") ||
            lower.contains("unsupported application") ||
            lower.contains("unsupported user-agent") ||
            lower.contains("client not supported")
        ) {
            return true
        }

        // Check if content is HTML without any proxy protocol links
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
        val samples = listOf(
            "vless://93a4a0c8-2e02-4c28-bf3a-9e22e8d350b2@de-frankfurt.v2ray.net:443?type=ws&security=tls&path=%2Fvless-ws&sni=de-frankfurt.v2ray.net#🇩🇪 DE Frankfurt VLESS-WS",
            "vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@nl-amsterdam.v2ray.net:443?type=grpc&security=reality&sni=nl-amsterdam.v2ray.net#🇳🇱 NL Amsterdam Reality",
            "vless://11223344-5566-7788-9900-aabbccddeeff@us-east.v2ray.net:443?type=ws&security=tls&path=%2Ffast-route&sni=us-east.v2ray.net#🇺🇸 US Virginia HighSpeed",
            "vmess://ewogICJ2IjogIjIiLAogICJwcyI6ICLskpAganAgVG9reW8gVk1lc3MiLAogICJhZGQiOiAianAtdG9reW8udjJyYXkubmV0IiwKICAicG9ydCI6IDQ0MywKICAiaWQiOiAiOTNhNGEwYzgtMmUwMi00YzI4LWJmM2EtOWUyMmU4ZDM1MGIyIiwKICAiYWlkIjogMCwKICAibmV0IjogIndzIiwKICAidHlwZSI6ICJub25lIiwKICAiaG9zdCI6ICJqcC10b2t5by52MnJheS5uZXQiLAogICJwYXRoIjogIi92bWVzcyIsCiAgInRscyI6ICJ0bHMiLAogICJzbmkiOiAianAtdG9reW8udjJyYXkubmV0Igp9",
            "trojan://password123@de-trojan.v2ray.net:443?security=tls&type=ws&path=%2Ftrojan#🇩🇪 DE Trojan Secure",
            "vless://44556677-8899-0011-2233-445566778899@fi-helsinki.v2ray.net:8443?type=ws&security=tls&path=%2Ffi-node#🇫🇮 FI Helsinki Turbo"
        )

        return samples.mapNotNull { parseLink(it, "sample_sub", "Sample V2Ray Group") }
    }
}

