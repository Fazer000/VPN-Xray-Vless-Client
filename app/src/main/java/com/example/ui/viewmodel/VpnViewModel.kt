package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AppRule
import com.example.data.model.Subscription
import com.example.data.model.VpnServer
import com.example.data.repository.VpnRepository
import com.example.util.AppUpdateInfo
import com.example.util.DownloadState
import com.example.util.UpdateManager
import com.example.vpn.XrayVpnService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VpnRepository(application)
    private val updateManager = UpdateManager(application)
    private val prefs = application.getSharedPreferences("xray_vpn_prefs", Context.MODE_PRIVATE)

    // Update States
    private val _updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val updateInfo: StateFlow<AppUpdateInfo?> = _updateInfo.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    // Room Flows
    val servers: StateFlow<List<VpnServer>> = repository.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptions: StateFlow<List<Subscription>> = repository.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appRules: StateFlow<List<AppRule>> = repository.allAppRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected Server State
    private val _selectedServerId = MutableStateFlow(prefs.getString("selected_server_id", null))
    val selectedServerId: StateFlow<String?> = _selectedServerId.asStateFlow()

    val selectedServer: StateFlow<VpnServer?> = combine(servers, selectedServerId) { serverList, id ->
        serverList.find { it.id == id } ?: serverList.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Ping Testing States
    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _pingProgress = MutableStateFlow(Pair(0, 0))
    val pingProgress: StateFlow<Pair<Int, Int>> = _pingProgress.asStateFlow()

    private val _activePingGroup = MutableStateFlow<String?>(null)
    val activePingGroup: StateFlow<String?> = _activePingGroup.asStateFlow()

    // Subscriptions Action State
    private val _subStateMessage = MutableStateFlow<String?>(null)
    val subStateMessage: StateFlow<String?> = _subStateMessage.asStateFlow()

    private val _isSubLoading = MutableStateFlow(false)
    val isSubLoading: StateFlow<Boolean> = _isSubLoading.asStateFlow()

    // Split Tunneling States
    private val _splitTunnelEnabled = MutableStateFlow(prefs.getBoolean("split_tunnel_enabled", false))
    val splitTunnelEnabled: StateFlow<Boolean> = _splitTunnelEnabled.asStateFlow()

    private val _splitTunnelMode = MutableStateFlow(prefs.getString("split_tunnel_mode", "PROXY") ?: "PROXY")
    val splitTunnelMode: StateFlow<String> = _splitTunnelMode.asStateFlow() // "PROXY" or "BYPASS"

    // VPN Service State Flows
    val vpnState = XrayVpnService.vpnState
    val rxBytes = XrayVpnService.rxBytes
    val txBytes = XrayVpnService.txBytes

    init {
        viewModelScope.launch {
            repository.loadSampleData()
            repository.syncAppRules()
        }
    }

    fun selectServer(serverId: String) {
        _selectedServerId.value = serverId
        prefs.edit().putString("selected_server_id", serverId).apply()
    }

    fun togglePin(server: VpnServer) {
        viewModelScope.launch {
            repository.togglePin(server.id, !server.isPinned)
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            repository.deleteServer(serverId)
        }
    }

    fun addServerManually(rawLink: String, groupName: String = "Manual", onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val success = repository.addServerManually(rawLink, groupName)
            if (success) onSuccess() else onError("Invalid VLESS or VMess link format.")
        }
    }

    fun pingSingleServer(server: VpnServer) {
        viewModelScope.launch {
            repository.pingServer(server)
        }
    }

    fun pingAllServers() {
        if (_isPinging.value) return
        viewModelScope.launch {
            _isPinging.value = true
            _activePingGroup.value = "ALL"
            val targetList = servers.value
            _pingProgress.value = Pair(0, targetList.size)

            repository.pingServers(targetList) { completed, total ->
                _pingProgress.value = Pair(completed, total)
            }

            _isPinging.value = false
            _activePingGroup.value = null
        }
    }

    fun pingGroupServers(groupName: String) {
        if (_isPinging.value) return
        viewModelScope.launch {
            _isPinging.value = true
            _activePingGroup.value = groupName
            val targetList = servers.value.filter { it.groupName == groupName }
            _pingProgress.value = Pair(0, targetList.size)

            repository.pingServers(targetList) { completed, total ->
                _pingProgress.value = Pair(completed, total)
            }

            _isPinging.value = false
            _activePingGroup.value = null
        }
    }

    fun addSubscription(url: String, name: String = "") {
        viewModelScope.launch {
            _isSubLoading.value = true
            _subStateMessage.value = "Загрузка подписки..."
            val result = repository.addSubscription(url, name)
            _isSubLoading.value = false
            result.onSuccess {
                _subStateMessage.value = "Подписка успешно обновлена (${it.serverCount} серверов)."
            }.onFailure { err ->
                _subStateMessage.value = "Ошибка: ${err.localizedMessage ?: "Не удалось загрузить подписку"}"
            }
        }
    }

    fun updateSubscription(subId: String) {
        viewModelScope.launch {
            _isSubLoading.value = true
            _subStateMessage.value = "Обновление подписки..."
            val result = repository.updateSubscription(subId)
            _isSubLoading.value = false
            result.onSuccess {
                _subStateMessage.value = "Подписка обновлена (${it.serverCount} серверов)."
            }.onFailure { err ->
                _subStateMessage.value = "Ошибка: ${err.localizedMessage ?: "Не удалось обновить"}"
            }
        }
    }

    fun updateAllSubscriptions() {
        if (_isSubLoading.value) return
        viewModelScope.launch {
            _isSubLoading.value = true
            _subStateMessage.value = "Обновление всех подписок..."
            val list = subscriptions.value
            var totalServers = 0
            for (sub in list) {
                val res = repository.updateSubscription(sub.id)
                res.getOrNull()?.let {
                    totalServers += it.serverCount
                }
            }
            _isSubLoading.value = false
            _subStateMessage.value = "Все подписки обновлены (всего серверов: $totalServers)."
        }
    }

    fun deleteSubscription(subId: String) {
        viewModelScope.launch {
            repository.deleteSubscription(subId)
        }
    }

    fun clearSubStateMessage() {
        _subStateMessage.value = null
    }

    fun setSplitTunnelEnabled(enabled: Boolean) {
        _splitTunnelEnabled.value = enabled
        prefs.edit().putBoolean("split_tunnel_enabled", enabled).apply()
    }

    fun setSplitTunnelMode(mode: String) { // "PROXY" or "BYPASS"
        _splitTunnelMode.value = mode
        prefs.edit().putString("split_tunnel_mode", mode).apply()
    }

    fun toggleAppProxied(packageName: String, isProxied: Boolean) {
        viewModelScope.launch {
            repository.updateAppRule(packageName, isProxied)
        }
    }

    fun setAllAppsProxied(isProxied: Boolean) {
        viewModelScope.launch {
            repository.setAllAppRulesProxied(isProxied)
        }
    }

    fun checkForAppUpdates() {
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            val info = updateManager.checkForUpdates()
            _updateInfo.value = info
            _isCheckingUpdate.value = false
        }
    }

    fun startDownloadAndUpdate(url: String) {
        viewModelScope.launch {
            updateManager.downloadApk(url).collect { state ->
                _downloadState.value = state
                if (state is DownloadState.Finished) {
                    installDownloadedApk(state.apkFile)
                }
            }
        }
    }

    fun installDownloadedApk(file: File) {
        updateManager.installApk(file)
    }

    fun dismissUpdateDialog() {
        _updateInfo.value = null
        _downloadState.value = DownloadState.Idle
    }

    fun toggleVpnConnection(context: Context) {
        val currentState = vpnState.value
        val server = selectedServer.value ?: return

        val intent = Intent(context, XrayVpnService::class.java)
        if (currentState == XrayVpnService.State.CONNECTED || currentState == XrayVpnService.State.CONNECTING) {
            intent.action = XrayVpnService.ACTION_DISCONNECT
        } else {
            intent.action = XrayVpnService.ACTION_CONNECT
            intent.putExtra(XrayVpnService.EXTRA_SERVER_ID, server.id)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_NAME, server.name)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_HOST, server.host)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_PORT, server.port)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_PROTOCOL, server.protocol.name)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_UUID, server.uuid)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_SECURITY, server.security)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_NETWORK, server.network)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_PATH, server.path)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_SNI, server.sni)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_PUBLIC_KEY, server.publicKey)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_SHORT_ID, server.shortId)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_FINGERPRINT, server.fingerprint)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_FLOW, server.flow)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_SERVICE_NAME, server.serviceName)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_ALPN, server.alpn)
            intent.putExtra(XrayVpnService.EXTRA_SERVER_RAW_LINK, server.rawLink)
            intent.putExtra(XrayVpnService.EXTRA_SPLIT_TUNNEL_ENABLED, _splitTunnelEnabled.value)
            intent.putExtra(XrayVpnService.EXTRA_SPLIT_MODE, _splitTunnelMode.value)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
