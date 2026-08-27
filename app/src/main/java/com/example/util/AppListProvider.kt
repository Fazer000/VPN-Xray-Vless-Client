package com.example.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.data.model.AppRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppListProvider {

    suspend fun getInstalledApps(context: Context, existingRulesMap: Map<String, Boolean> = emptyMap()): List<AppRule> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val selfPackage = context.packageName

        packages.filter { it.packageName != selfPackage }
            .map { appInfo ->
                val appName = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    appInfo.packageName
                }

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isProxied = existingRulesMap[appInfo.packageName] ?: false

                AppRule(
                    packageName = appInfo.packageName,
                    appName = appName,
                    isProxied = isProxied,
                    isSystemApp = isSystem
                )
            }
            .sortedBy { it.appName.lowercase() }
    }
}
