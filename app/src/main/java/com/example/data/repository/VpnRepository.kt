package com.example.data.repository

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.model.AppRule
import com.example.data.model.Subscription
import com.example.data.model.VpnServer
import com.example.util.AppListProvider
import com.example.util.PingTester
import com.example.util.ProtocolParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

class VpnRepository(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val serverDao = db.vpnServerDao()
    private val subDao = db.subscriptionDao()
    private val ruleDao = db.appRuleDao()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val allServers: Flow<List<VpnServer>> = serverDao.getAllServers()
    val allSubscriptions: Flow<List<Subscription>> = subDao.getAllSubscriptions()
    val allAppRules: Flow<List<AppRule>> = ruleDao.getAllRules()

    suspend fun getServerById(id: String): VpnServer? = serverDao.getServerById(id)

    suspend fun togglePin(serverId: String, isPinned: Boolean) {
        serverDao.setPinned(serverId, isPinned)
    }

    suspend fun addServerManually(rawLink: String, groupName: String = "Manual"): Boolean {
        val server = ProtocolParser.parseLink(rawLink, "manual", groupName)
        return if (server != null) {
            serverDao.insertServer(server)
            true
        } else {
            false
        }
    }

    suspend fun deleteServer(serverId: String) {
        serverDao.deleteServerById(serverId)
    }

    suspend fun loadSampleData() = withContext(Dispatchers.IO) {
        // Clean up any previously inserted demo/sample subscriptions or servers
        try {
            serverDao.deleteServersBySubscription("sample_sub")
            subDao.deleteSubscriptionById("sample_sub")
        } catch (_: Exception) {}
    }

    suspend fun addSubscription(url: String, customName: String = ""): Result<Subscription> = withContext(Dispatchers.IO) {
        try {
            val trimmedUrl = url.trim()
            val subId = UUID.nameUUIDFromBytes(trimmedUrl.toByteArray()).toString()
            val name = customName.ifEmpty { "Sub-${subId.take(6)}" }

            val isNetworkUrl = trimmedUrl.startsWith("http://", ignoreCase = true) || trimmedUrl.startsWith("https://", ignoreCase = true)

            var parsedServers = emptyList<VpnServer>()

            if (!isNetworkUrl) {
                // Direct JSON or URI link input
                parsedServers = ProtocolParser.parseSubscriptionContent(trimmedUrl, subId, name)
                if (parsedServers.isEmpty()) {
                    return@withContext Result.failure(Exception("Не удалось распознать конфигурацию JSON или V2Ray ссылку."))
                }
            } else {
                // Remote subscription URL download
                val deviceModel = android.os.Build.MODEL.ifBlank { "Android Device" }
                val verOs = android.os.Build.VERSION.RELEASE.ifBlank { "13" }
                val locale = java.util.Locale.getDefault().language.ifBlank { "ru" }

                var lastErrorMessage = ""

                try {
                    val request = Request.Builder()
                        .url(trimmedUrl)
                        .header("User-Agent", "Happ/3.13.0")
                        .header("X-Device-Os", "Android")
                        .header("X-Device-Locale", if (locale.isNotBlank()) locale else "ru")
                        .header("X-Device-Model", deviceModel)
                        .header("X-Ver-Os", verOs)
                        .header("X-Hwid", "74jf74nf8f4jr5je")
                        .header("Accept-Encoding", "gzip")
                        .header("Connection", "keep-alive")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        lastErrorMessage = "HTTP ${response.code}"
                    } else {
                        val rawBytes = response.body?.bytes() ?: byteArrayOf()
                        val body = try {
                            if (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
                                java.io.InputStreamReader(java.util.zip.GZIPInputStream(rawBytes.inputStream()), Charsets.UTF_8).readText()
                            } else {
                                String(rawBytes, Charsets.UTF_8)
                            }
                        } catch (_: Exception) {
                            String(rawBytes, Charsets.UTF_8)
                        }

                        if (body.isNotBlank()) {
                            val servers = ProtocolParser.parseSubscriptionContent(body, subId, name)
                            if (servers.isNotEmpty()) {
                                parsedServers = servers
                            } else {
                                if (ProtocolParser.isHtmlOrErrorBody(body)) {
                                    lastErrorMessage = "Панель подписки отклонила клиент."
                                } else {
                                    lastErrorMessage = "Не найдены серверы в ответе от подписки."
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    lastErrorMessage = "Ошибка подписки: ${e.localizedMessage}"
                }

                if (parsedServers.isEmpty()) {
                    val errorReason = lastErrorMessage.ifEmpty { "Не найдено действительных конфигураций в подписке." }
                    return@withContext Result.failure(Exception(errorReason))
                }
            }

            // Remove old servers for this subscription and insert updated ones
            serverDao.deleteServersBySubscription(subId)
            serverDao.insertServers(parsedServers)

            val subscription = Subscription(
                id = subId,
                name = name,
                url = if (isNetworkUrl) trimmedUrl else "Local Config (${parsedServers.size} nodes)",
                lastUpdated = System.currentTimeMillis(),
                serverCount = parsedServers.size
            )
            subDao.insertSubscription(subscription)

            Result.success(subscription)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateSubscription(subId: String): Result<Subscription> = withContext(Dispatchers.IO) {
        val sub = subDao.getSubscriptionById(subId)
            ?: return@withContext Result.failure(Exception("Subscription not found"))
        addSubscription(sub.url, sub.name)
    }

    suspend fun deleteSubscription(subId: String) = withContext(Dispatchers.IO) {
        serverDao.deleteServersBySubscription(subId)
        subDao.deleteSubscriptionById(subId)
    }

    suspend fun pingServer(server: VpnServer): Long = withContext(Dispatchers.IO) {
        val latency = PingTester.testPing(server)
        serverDao.updateLatency(server.id, latency, System.currentTimeMillis())
        latency
    }

    suspend fun pingServers(servers: List<VpnServer>, onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext
        val results = PingTester.testBatchPing(servers, onProgress = onProgress)
        val now = System.currentTimeMillis()
        results.forEach { (serverId, latency) ->
            serverDao.updateLatency(serverId, latency, now)
        }
    }

    suspend fun syncAppRules() = withContext(Dispatchers.IO) {
        val existingRules = ruleDao.getAllRules().first()
        val map = existingRules.associate { it.packageName to it.isProxied }
        val installedApps = AppListProvider.getInstalledApps(context, map)
        ruleDao.insertRules(installedApps)
    }

    suspend fun updateAppRule(packageName: String, isProxied: Boolean) {
        ruleDao.setRuleProxied(packageName, isProxied)
    }

    suspend fun setAllAppRulesProxied(isProxied: Boolean) {
        ruleDao.setAllRulesProxied(isProxied)
    }

    suspend fun getProxiedAppPackages(): List<String> {
        return ruleDao.getProxiedRules().map { it.packageName }
    }
}
