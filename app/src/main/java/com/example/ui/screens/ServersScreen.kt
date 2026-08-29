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
                        text = "Серверы VPN",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Доступно узлов: ${servers.size}",
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
                            contentDescription = "Тест пинга",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Пинг всех", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                placeholder = { Text("Поиск по названию, хосту...", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск", tint = TextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить", tint = TextSecondary)
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
                            contentDescription = "Серверы не найдены",
                            tint = TextSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Серверы не найдены",
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
                                    contentDescription = "Закрепленные",
                                    tint = CyberCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ЗАКРЕПЛЕННЫЕ СЕРВЕРЫ",
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
                                        contentDescription = "Группа",
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
                                            contentDescription = "Пинг группы",
                                            tint = CyberPurple,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Пинг группы", fontSize = 11.sp, color = CyberPurple)
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

    // Add Manual Link Bottom Sheet
    if (showAddDialog) {
        AddServerBottomSheet(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerBottomSheet(
    onDismiss: () -> Unit,
    onAdd: (link: String, group: String) -> Unit
) {
    var linkText by remember { mutableStateOf("") }
    var groupText by remember { mutableStateOf("Вручную") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CyberSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary.copy(alpha = 0.4f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text("Добавить узел VLESS / VMess", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(16.dp))

            Text("Вставьте ссылку vless:// или vmess://:", fontSize = 13.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = linkText,
                onValueChange = { linkText = it },
                placeholder = { Text("vless://...", fontSize = 12.sp, color = TextSecondary) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("add_server_link_input"),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = CyberSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text("Группа серверов:", fontSize = 13.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = groupText,
                onValueChange = { groupText = it },
                placeholder = { Text("Вручную", fontSize = 12.sp, color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = CyberSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Отмена", color = TextSecondary)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { onAdd(linkText, groupText) },
                    enabled = linkText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("confirm_add_server_btn")
                ) {
                    Text("Добавить узел", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
