package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.ui.viewmodel.VpnViewModel
import com.example.util.LogManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VpnViewModel,
    onBack: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToSplitTunnel: () -> Unit,
    onNavigateToSubscriptions: () -> Unit
) {
    val context = LocalContext.current
    val dnsProvider by viewModel.dnsProvider.collectAsState()
    val enableIpv6 by viewModel.enableIpv6.collectAsState()
    val antiDpiEnabled by viewModel.antiDpiEnabled.collectAsState()
    var showDnsDialog by remember { mutableStateOf(false) }

    val dnsOptions = listOf("Cloudflare DoH (1.1.1.1)", "Google DoH (8.8.8.8)", "Quad9 DoH (9.9.9.9)", "System Native DNS")

    if (showDnsDialog) {
        ModalBottomSheet(
            onDismissRequest = { showDnsDialog = false },
            containerColor = CyberSurface,
            scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary.copy(alpha = 0.4f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("Выберите DNS провайдер", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(16.dp))

                dnsOptions.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.setDnsProvider(option)
                                showDnsDialog = false
                                LogManager.i("Settings", "DNS Provider updated to: $option")
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = dnsProvider == option,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = CyberCyan)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = option, color = TextPrimary, fontSize = 15.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showDnsDialog = false }) {
                        Text("Закрыть", color = CyberCyan)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    Scaffold(
        containerColor = CyberBackground,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_btn")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = TextPrimary)
                    }
                },
                title = {
                    Text(
                        text = "Настройки и ядро",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberSurface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Diagnostics & Terminal
            SettingsCategoryHeader("Диагностика и логи")

            SettingsItemCard(
                icon = Icons.Default.Terminal,
                iconTint = CyberCyan,
                title = "Логи ядра и терминал",
                subtitle = "Просмотр VLESS подключений, TLS хэндшейков и DoH DNS",
                testTag = "settings_logs_btn",
                onClick = onNavigateToLogs
            )

            SettingsItemCard(
                icon = Icons.Default.Speed,
                iconTint = CyberGreen,
                title = "Проверить пинг всех серверов",
                subtitle = "Измерение задержки TCP сокетов для всех узлов",
                testTag = "settings_ping_btn",
                onClick = {
                    viewModel.pingAllServers()
                    Toast.makeText(context, "Измерение задержки всех серверов...", Toast.LENGTH_SHORT).show()
                }
            )

            // Section 2: Tunneling & Engine
            SettingsCategoryHeader("Тоннелирование и движок")

            SettingsToggleCard(
                icon = Icons.Default.Security,
                iconTint = CyberGreen,
                title = "Защита Anti-DPI (Обход блокировок)",
                subtitle = "Дробление WS фреймов, ALPN h2 и Chrome заголовки для устойчивости",
                checked = antiDpiEnabled,
                onCheckedChange = {
                    viewModel.setAntiDpiEnabled(it)
                    LogManager.i("Settings", "Anti-DPI Stealth mode set to $it")
                }
            )

            SettingsItemCard(
                icon = Icons.Default.AltRoute,
                iconTint = CyberAmber,
                title = "Разделение трафика (Split Tunneling)",
                subtitle = "Выбор приложений для обхода или направленности в VPN",
                testTag = "settings_split_btn",
                onClick = onNavigateToSplitTunnel
            )

            SettingsItemCard(
                icon = Icons.Default.Dns,
                iconTint = CyberCyan,
                title = "Протокол DNS резолвера",
                subtitle = dnsProvider,
                testTag = "settings_dns_btn",
                onClick = { showDnsDialog = true }
            )

            SettingsToggleCard(
                icon = Icons.Default.Router,
                iconTint = CyberPurple,
                title = "Тоннелирование IPv6 трафика",
                subtitle = "Перенаправление IPv6 пакетов через TUN интерфейс",
                checked = enableIpv6,
                onCheckedChange = {
                    viewModel.setEnableIpv6(it)
                    LogManager.i("Settings", "IPv6 tunneling changed to $it")
                }
            )

            // Section 3: Subscriptions & Updates
            SettingsCategoryHeader("Подписки и обслуживание")

            SettingsItemCard(
                icon = Icons.Default.RssFeed,
                iconTint = CyberCyan,
                title = "Подписки V2Ray",
                subtitle = "Управление ссылками автообновления VLESS / Trojan / SOCKS5",
                testTag = "settings_subscriptions_btn",
                onClick = onNavigateToSubscriptions
            )

            SettingsItemCard(
                icon = Icons.Default.SystemUpdate,
                iconTint = CyberGreen,
                title = "Проверить обновления",
                subtitle = "Запрос актуальной версии приложения",
                testTag = "settings_update_btn",
                onClick = { viewModel.checkForAppUpdates() }
            )

            SettingsItemCard(
                icon = Icons.Default.DeleteSweep,
                iconTint = CyberRed,
                title = "Очистить логи диагностики",
                subtitle = "Очистка кэшированного буфера терминала",
                testTag = "settings_clear_logs_btn",
                onClick = {
                    LogManager.clear()
                    Toast.makeText(context, "Буфер логов очищен", Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Footer / About Info
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CyberSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, CyberSurfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CyberCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = CyberCyan)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "XrayFlow VPN v1.0.4",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Движок Kotlin TUN • VLESS / Trojan / WS / TLS",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCategoryHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = CyberCyan,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
fun SettingsItemCard(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag(testTag),
        color = CyberSurface,
        border = BorderStroke(1.dp, CyberSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun SettingsToggleCard(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        color = CyberSurface,
        border = BorderStroke(1.dp, CyberSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CyberCyan,
                    checkedTrackColor = CyberCyan.copy(alpha = 0.3f),
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = CyberSurfaceVariant
                )
            )
        }
    }
}
