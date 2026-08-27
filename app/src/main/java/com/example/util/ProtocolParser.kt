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
            else -> null
        }
    }

    private fun parseVless(link: String, subscriptionId: String, defaultGroup: String): VpnServer? {
        return try {
            // vless://uuid@host:port?params#remark
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

            // Auto group assignment based on country tag or subscription
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

    fun parseSubscriptionContent(content: String, subscriptionId: String, groupName: String): List<VpnServer> {
        val rawLines = mutableListOf<String>()
        val decoded = decodeBase64Safe(content)

        val targetContent = if (decoded.contains("vless://") || decoded.contains("vmess://")) decoded else content
        targetContent.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                rawLines.add(trimmed)
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

    private fun decodeBase64Safe(input: String): String {
        return try {
            val clean = input.trim().replace("\r", "").replace("\n", "")
            val decodedBytes = Base64.decode(clean, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE)
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
            "vless://44556677-8899-0011-2233-445566778899@fi-helsinki.v2ray.net:8443?type=ws&security=tls&path=%2Ffi-node#🇫🇮 FI Helsinki Turbo"
        )

        return samples.mapNotNull { parseLink(it, "sample_sub", "Sample V2Ray Group") }
    }
}
