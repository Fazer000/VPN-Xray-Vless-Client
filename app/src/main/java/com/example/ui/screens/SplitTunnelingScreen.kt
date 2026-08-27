package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppRule
import com.example.ui.theme.*
import com.example.ui.viewmodel.VpnViewModel

@Composable
fun SplitTunnelingScreen(
    viewModel: VpnViewModel
) {
    val enabled by viewModel.splitTunnelEnabled.collectAsState()
    val mode by viewModel.splitTunnelMode.collectAsState() // "PROXY" or "BYPASS"
    val appRules by viewModel.appRules.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = All, 1 = User Apps, 2 = System Apps

    val filteredApps = remember(appRules, searchQuery, selectedTab) {
        appRules.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                    app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)

            val matchesTab = when (selectedTab) {
                1 -> !app.isSystemApp
                2 -> app.isSystemApp
                else -> true
            }

            matchesSearch && matchesTab
        }
    }

    val proxiedCount = remember(appRules) { appRules.count { it.isProxied } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Split Tunneling",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Control per-app traffic routing",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            // Master Switch
            Switch(
                checked = enabled,
                onCheckedChange = { viewModel.setSplitTunnelEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = CyberCyan,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = CyberSurface
                ),
                modifier = Modifier.testTag("split_tunnel_master_switch")
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Mode Selector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSurface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ROUTING MODE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = mode == "PROXY",
                        onClick = { viewModel.setSplitTunnelMode("PROXY") },
                        label = { Text("Proxy Selected ($proxiedCount)") },
                        leadingIcon = {
                            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberCyan,
                            selectedLabelColor = Color.Black,
                            selectedLeadingIconColor = Color.Black
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_proxy_chip")
                    )

                    FilterChip(
                        selected = mode == "BYPASS",
                        onClick = { viewModel.setSplitTunnelMode("BYPASS") },
                        label = { Text("Bypass Selected") },
                        leadingIcon = {
                            Icon(Icons.Default.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberPurple,
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_bypass_chip")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("app_search_input"),
            placeholder = { Text("Search installed applications...", color = TextSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberCyan,
                unfocusedBorderColor = CyberSurfaceVariant,
                focusedContainerColor = CyberSurface,
                unfocusedContainerColor = CyberSurface
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Filter Tabs & Quick Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp)),
                containerColor = CyberSurface,
                contentColor = CyberCyan,
                indicator = {}
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("All (${appRules.size})", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("User", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("System", fontSize = 12.sp) }
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Select All / Deselect All Toggle
            IconButton(
                onClick = {
                    val allProxied = proxiedCount == appRules.size
                    viewModel.setAllAppsProxied(!allProxied)
                },
                modifier = Modifier.testTag("toggle_all_apps_btn")
            ) {
                Icon(
                    imageVector = if (proxiedCount == appRules.size) Icons.Default.SelectAll else Icons.Default.Deselect,
                    contentDescription = "Toggle All",
                    tint = CyberCyan
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // App List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                AppRuleCard(
                    app = app,
                    onToggle = { isChecked ->
                        viewModel.toggleAppProxied(app.packageName, isChecked)
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun AppRuleCard(
    app: AppRule,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = CircleShape,
                    color = CyberSurfaceVariant,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (app.isSystemApp) Icons.Default.Android else Icons.Default.Apps,
                            contentDescription = null,
                            tint = if (app.isSystemApp) CyberAmber else CyberCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = app.appName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = app.packageName,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
            }

            Switch(
                checked = app.isProxied,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = CyberCyan,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = CyberSurfaceVariant
                ),
                modifier = Modifier.testTag("app_switch_${app.packageName}")
            )
        }
    }
}
