package com.example.ui.screens

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VpnServer
import com.example.ui.components.UpdateDialog
import com.example.ui.theme.*
import com.example.ui.viewmodel.VpnViewModel
import com.example.vpn.XrayVpnService

@Composable
fun HomeScreen(
    viewModel: VpnViewModel,
    onNavigateToServers: () -> Unit,
    onNavigateToSplitTunnel: () -> Unit
) {
    val context = LocalContext.current
    val vpnState by viewModel.vpnState.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val rxBytes by viewModel.rxBytes.collectAsState()
    val txBytes by viewModel.txBytes.collectAsState()
    val splitTunnelEnabled by viewModel.splitTunnelEnabled.collectAsState()

    val updateInfo by viewModel.updateInfo.collectAsState()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.toggleVpnConnection(context)
        }
    }

    UpdateDialog(
        updateInfo = updateInfo,
        isChecking = isCheckingUpdate,
        downloadState = downloadState,
        onCheckUpdate = { viewModel.checkForAppUpdates() },
        onDownloadAndUpdate = { url -> viewModel.startDownloadAndUpdate(url) },
        onDismiss = { viewModel.dismissUpdateDialog() }
    )

    val isConnected = vpnState == XrayVpnService.State.CONNECTED
    val isConnecting = vpnState == XrayVpnService.State.CONNECTING

    // Pulsing animation for active connection ring
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "App Logo",
                    tint = CyberCyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Xray flow",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Check Update Icon Button
                IconButton(
                    onClick = { viewModel.checkForAppUpdates() },
                    modifier = Modifier.testTag("home_update_btn")
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = CyberCyan,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Check for Updates",
                            tint = CyberCyan
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Split Tunnel Indicator Pill
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onNavigateToSplitTunnel() }
                        .testTag("home_split_tunnel_btn"),
                    color = if (splitTunnelEnabled) CyberCyan.copy(alpha = 0.15f) else CyberSurface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AltRoute,
                            contentDescription = "Split Tunneling",
                            tint = if (splitTunnelEnabled) CyberCyan else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (splitTunnelEnabled) "Split: ON" else "Split: OFF",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (splitTunnelEnabled) CyberCyan else TextSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Connection State Badge
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = when {
                isConnected -> CyberGreen.copy(alpha = 0.15f)
                isConnecting -> CyberAmber.copy(alpha = 0.15f)
                else -> CyberSurface
            },
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                when {
                    isConnected -> CyberGreen.copy(alpha = 0.5f)
                    isConnecting -> CyberAmber.copy(alpha = 0.5f)
                    else -> Color.Transparent
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isConnected -> CyberGreen
                                isConnecting -> CyberAmber
                                else -> TextSecondary
                            }
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isConnected -> "PROTECTED & ENCRYPTED"
                        isConnecting -> "ESTABLISHING XRAY TUNNEL..."
                        else -> "DISCONNECTED"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isConnected -> CyberGreen
                        isConnecting -> CyberAmber
                        else -> TextSecondary
                    }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Main Glow Connect Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(240.dp)
        ) {
            // Pulse Ring Behind
            if (isConnected || isConnecting) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) CyberCyan.copy(alpha = 0.2f) else CyberAmber.copy(alpha = 0.2f)
                        )
                )
            }

            // Power Button Body
            Box(
                modifier = Modifier
                    .size(170.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = if (isConnected) listOf(CyberCyan, CyberPurple)
                            else if (isConnecting) listOf(CyberAmber, CyberSurface)
                            else listOf(CyberSurfaceVariant, CyberSurface)
                        )
                    )
                    .border(
                        width = 4.dp,
                        brush = Brush.linearGradient(
                            colors = if (isConnected) listOf(CyberCyan, CyberGreen)
                            else listOf(Color.White.copy(alpha = 0.2f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
                    .clickable {
                        val intent = VpnService.prepare(context)
                        if (intent != null) {
                            vpnPermissionLauncher.launch(intent)
                        } else {
                            viewModel.toggleVpnConnection(context)
                        }
                    }
                    .testTag("connect_vpn_button"),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Connect Toggle",
                        tint = if (isConnected) Color.White else TextPrimary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isConnected) "STOP" else if (isConnecting) "WAIT" else "START",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isConnected) Color.White else TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Live Speed Metrics Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSurface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                // Download Metric
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Download Traffic",
                        tint = CyberGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Download", fontSize = 11.sp, color = TextSecondary)
                        Text(
                            text = formatBytes(rxBytes),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }

                Divider(
                    modifier = Modifier
                        .height(36.dp)
                        .width(1.dp),
                    color = CyberSurfaceVariant
                )

                // Upload Metric
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Upload Traffic",
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Upload", fontSize = 11.sp, color = TextSecondary)
                        Text(
                            text = formatBytes(txBytes),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        // Active Server Selector Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToServers() }
                .testTag("select_server_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberSurfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = CyberPurple.copy(alpha = 0.2f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = "Server",
                                tint = CyberPurple,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = selectedServer?.name ?: "No Server Selected",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = selectedServer?.let { "${it.protocol} • ${it.groupName}" } ?: "Tap to choose a node",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    selectedServer?.let { s ->
                        LatencyBadge(latencyMs = s.latencyMs)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Select Server",
                        tint = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun LatencyBadge(latencyMs: Long) {
    val (color, text) = when {
        latencyMs < 0 -> Pair(TextSecondary, "Untested")
        latencyMs in 0..120 -> Pair(CyberGreen, "${latencyMs}ms")
        latencyMs in 121..300 -> Pair(CyberAmber, "${latencyMs}ms")
        else -> Pair(CyberRed, if (latencyMs == -2L) "Timeout" else "${latencyMs}ms")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format("%.2f MB", mb)
        kb >= 1.0 -> String.format("%.1f KB", kb)
        else -> "$bytes B"
    }
}
