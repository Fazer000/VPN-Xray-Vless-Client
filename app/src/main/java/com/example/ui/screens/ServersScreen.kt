package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.data.model.VpnProtocol
import com.example.data.model.VpnServer
import com.example.ui.theme.*
import com.example.ui.viewmodel.VpnViewModel

@Composable
fun ServersScreen(
    viewModel: VpnViewModel,
    onServerSelected: (VpnServer) -> Unit
) {
    val servers by viewModel.servers.collectAsState()
    val selectedServerId by viewModel.selectedServerId.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()
    val pingProgress by viewModel.pingProgress.collectAsState()
    val activePingGroup by viewModel.activePingGroup.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredServers = remember(servers, searchQuery) {
        if (searchQuery.isBlank()) servers
        else servers.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.groupName.contains(searchQuery, ignoreCase = true) ||
            it.host.contains(searchQuery, ignoreCase = true)
        }
    }

    val pinnedServers = remember(filteredServers) {
        filteredServers.filter { it.isPinned }
    }

    val groupedServers = remember(filteredServers) {
        filteredServers.filter { !it.isPinned }
            .groupBy { it.groupName }
    }

    Scaffold(
        containerColor = CyberBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = CyberCyan,
                contentColor = Color.Black,
                modifier = Modifier.testTag("add_server_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add VLESS/VMess")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Screen Header & Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "VPN Servers",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${servers.size} nodes available",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                // Global Test All Latencies Button
                Button(
                    onClick = { viewModel.pingAllServers() },
                    enabled = !isPinging,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberPurple,
                        disabledContainerColor = CyberSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("test_all_pings_btn")
                ) {
                    if (isPinging && activePingGroup == "ALL") {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${pingProgress.first}/${pingProgress.second}", fontSize = 12.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Ping All",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test All", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Search Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_search_input"),
                placeholder = { Text("Search by country, name, or host...", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = CyberSurfaceVariant,
                    focusedContainerColor = CyberSurface,
                    unfocusedContainerColor = CyberSurface
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Server List View
            if (filteredServers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = "No Servers",
                            tint = TextSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No servers found",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Pinned Servers Section
                    if (pinnedServers.isNotEmpty()) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = "Pinned",
                                    tint = CyberCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "PINNED SERVERS",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberCyan
                                )
                            }
                        }

                        items(pinnedServers, key = { "pinned_${it.id}" }) { server ->
                            ServerCard(
                                server = server,
                                isSelected = server.id == selectedServerId,
                                onSelect = {
                                    viewModel.selectServer(server.id)
                                    onServerSelected(server)
                                },
                                onTogglePin = { viewModel.togglePin(server) },
                                onPingSingle = { viewModel.pingSingleServer(server) },
                                onDelete = { viewModel.deleteServer(server.id) }
                            )
                        }
                    }

                    // Grouped Servers Section
                    groupedServers.forEach { (groupName, groupList) ->
                        item(key = "header_$groupName") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.FolderSpecial,
                                        contentDescription = "Group",
                                        tint = CyberPurple,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = groupName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = CyberSurfaceVariant
                                    ) {
                                        Text(
                                            text = "${groupList.size}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextSecondary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                // Separate Group Ping Button
                                OutlinedButton(
                                    onClick = { viewModel.pingGroupServers(groupName) },
                                    enabled = !isPinging,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberPurple.copy(alpha = 0.5f)),
                                    modifier = Modifier.testTag("ping_group_${groupName}_btn")
                                ) {
                                    if (isPinging && activePingGroup == groupName) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            color = CyberPurple,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("${pingProgress.first}/${pingProgress.second}", fontSize = 11.sp, color = CyberPurple)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.NetworkCheck,
                                            contentDescription = "Ping Group",
                                            tint = CyberPurple,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Ping Group", fontSize = 11.sp, color = CyberPurple)
                                    }
                                }
                            }
                        }

                        items(groupList, key = { it.id }) { server ->
                            ServerCard(
                                server = server,
                                isSelected = server.id == selectedServerId,
                                onSelect = {
                                    viewModel.selectServer(server.id)
                                    onServerSelected(server)
                                },
                                onTogglePin = { viewModel.togglePin(server) },
                                onPingSingle = { viewModel.pingSingleServer(server) },
                                onDelete = { viewModel.deleteServer(server.id) }
                            )
                        }
                    }

                    item { Spacer(modifier = Modifier.height(72.dp)) }
                }
            }
        }
    }

    // Add Manual Link Dialog
    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { link, group ->
                viewModel.addServerManually(
                    rawLink = link,
                    groupName = group,
                    onSuccess = { showAddDialog = false },
                    onError = {}
                )
            }
        )
    }
}

@Composable
fun ServerCard(
    server: VpnServer,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit,
    onPingSingle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("server_card_${server.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CyberSurfaceVariant else CyberSurface
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, CyberCyan) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Radio Check
                RadioButton(
                    selected = isSelected,
                    onClick = onSelect,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = CyberCyan,
                        unselectedColor = TextSecondary
                    )
                )

                Spacer(modifier = Modifier.width(6.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Protocol Tag
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (server.protocol == VpnProtocol.VLESS) CyberCyan.copy(alpha = 0.2f) else CyberPurple.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = server.protocol.name,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (server.protocol == VpnProtocol.VLESS) CyberCyan else CyberPurple,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // Network / Security Tag
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = CyberSurfaceVariant
                        ) {
                            Text(
                                text = "${server.network.uppercase()} • ${server.security.uppercase()}",
                                fontSize = 10.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Right Actions & Ping
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Ping Badge Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPingSingle() }
                ) {
                    LatencyBadge(latencyMs = server.latencyMs)
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Pin Button
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pin Server",
                        tint = if (server.isPinned) CyberCyan else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (link: String, group: String) -> Unit
) {
    var linkText by remember { mutableStateOf("") }
    var groupText by remember { mutableStateOf("Manual") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberSurface,
        title = {
            Text("Add VLESS / VMess Server", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text("Paste your vless:// or vmess:// link:", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    placeholder = { Text("vless://...", fontSize = 12.sp, color = TextSecondary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_server_link_input"),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Group Name:", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = groupText,
                    onValueChange = { groupText = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(linkText, groupText) },
                enabled = linkText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                modifier = Modifier.testTag("confirm_add_server_btn")
            ) {
                Text("Add Node", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
