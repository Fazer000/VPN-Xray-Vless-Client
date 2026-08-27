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
        val existingServers = allServers.first()
        if (existingServers.isEmpty()) {
            val samples = ProtocolParser.getSampleServers()
            serverDao.insertServers(samples)

            val sampleSub = Subscription(
                id = "sample_sub",
                name = "V2RayTun Demo Subscription",
                url = "https://raw.githubusercontent.com/v2fly/fakedata/main/sub.txt",
                lastUpdated = System.currentTimeMillis(),
                serverCount = samples.size
            )
            subDao.insertSubscription(sampleSub)
        }
    }

    suspend fun addSubscription(url: String, customName: String = ""): Result<Subscription> = withContext(Dispatchers.IO) {
        try {
            val trimmedUrl = url.trim()
            val request = Request.Builder()
                .url(trimmedUrl)
                .header("User-Agent", "v2rayTun/1.0.0 (Android) XrayVPN/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error code: ${response.code}"))
            }

            val body = response.body?.string() ?: ""
            val subId = UUID.nameUUIDFromBytes(trimmedUrl.toByteArray()).toString()
            val name = customName.ifEmpty { "Sub-${subId.take(6)}" }

            val servers = ProtocolParser.parseSubscriptionContent(body, subId, name)
            if (servers.isEmpty()) {
                return@withContext Result.failure(Exception("No valid VLESS/VMess servers found in subscription link."))
            }

            // Remove old servers for this subscription and insert updated ones
            serverDao.deleteServersBySubscription(subId)
            serverDao.insertServers(servers)

            val subscription = Subscription(
                id = subId,
                name = name,
                url = trimmedUrl,
                lastUpdated = System.currentTimeMillis(),
                serverCount = servers.size
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
