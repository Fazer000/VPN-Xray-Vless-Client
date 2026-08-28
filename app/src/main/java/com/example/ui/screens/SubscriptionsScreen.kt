package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Subscription
import com.example.data.model.VpnServer
import com.example.ui.theme.*
import com.example.ui.viewmodel.VpnViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SubscriptionsScreen(
    viewModel: VpnViewModel
) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val isSubLoading by viewModel.isSubLoading.collectAsState()
    val subStateMessage by viewModel.subStateMessage.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(subStateMessage) {
        subStateMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSubStateMessage()
        }
    }

    val totalServersCount = remember(servers) { servers.size }
    val lastUpdatedSub = remember(subscriptions) {
        subscriptions.maxByOrNull { it.lastUpdated }
    }

    val dateFormat = remember { SimpleDateFormat("dd MMM YYYY, HH:mm", Locale("ru")) }

    Scaffold(
        containerColor = CyberBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = CyberCyan,
                contentColor = Color.Black,
                modifier = Modifier.testTag("add_subscription_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Добавить подписку")
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

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Подписки V2Ray",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Управление источниками и фидами серверов",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                if (subscriptions.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.updateAllSubscriptions() },
                        enabled = !isSubLoading,
                        modifier = Modifier
                            .background(CyberSurface, RoundedCornerShape(10.dp))
                            .testTag("update_all_subs_btn")
                    ) {
                        if (isSubLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = CyberCyan,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Обновить все",
                                tint = CyberCyan
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (subscriptions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = CyberSurface,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RssFeed,
                                contentDescription = "Нет подписок",
                                tint = CyberCyan,
                                modifier = Modifier
                                    .padding(24.dp)
                                    .size(56.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Нет активных подписок",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Добавьте ссылку подписки V2Ray / V2RayTun,\nчтобы автоматически загрузить список серверов",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Добавить подписку", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Summary Information Dashboard Banner
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = CyberSurface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Analytics,
                                            contentDescription = "Сводка",
                                            tint = CyberCyan,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Сводка подписок",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = CyberGreen.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "● Активно",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CyberGreen,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Всего источников", fontSize = 11.sp, color = TextSecondary)
                                        Text(
                                            text = "${subscriptions.size}",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    }

                                    Column {
                                        Text("Импортировано узлов", fontSize = 11.sp, color = TextSecondary)
                                        Text(
                                            text = "$totalServersCount серверов",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CyberGreen
                                        )
                                    }

                                    Column {
                                        Text("Автообновление", fontSize = 11.sp, color = TextSecondary)
                                        Text(
                                            text = "Каждые 24 ч",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = CyberCyan
                                        )
                                    }
                                }

                                if (selectedServer != null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Divider(color = CyberSurfaceVariant)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Dns,
                                            contentDescription = "Активный сервер",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Выбран узел: ",
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = selectedServer?.name ?: "",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    items(subscriptions, key = { it.id }) { sub ->
                        val subServers = remember(servers, sub.id) {
                            servers.filter { it.subscriptionId == sub.id }
                        }

                        SubscriptionCard(
                            subscription = sub,
                            subServers = subServers,
                            isSubLoading = isSubLoading,
                            dateFormat = dateFormat,
                            onCopyUrl = {
                                clipboardManager.setText(AnnotatedString(sub.url))
                            },
                            onUpdate = { viewModel.updateSubscription(sub.id) },
                            onDelete = { viewModel.deleteSubscription(sub.id) }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(72.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { url, name ->
                viewModel.addSubscription(url, name)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun SubscriptionCard(
    subscription: Subscription,
    subServers: List<VpnServer>,
    isSubLoading: Boolean,
    dateFormat: SimpleDateFormat,
    onCopyUrl: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit
) {
    val protocolCounts = remember(subServers) {
        subServers.groupingBy { it.protocol.name }.eachCount()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sub_card_${subscription.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title & Quick Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.RssFeed,
                        contentDescription = "Subscription",
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = subscription.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1
                    )
                }

                Row {
                    IconButton(
                        onClick = onUpdate,
                        enabled = !isSubLoading,
                        modifier = Modifier.testTag("update_sub_btn_${subscription.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Обновить",
                            tint = CyberCyan
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_sub_btn_${subscription.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Удалить",
                            tint = CyberRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // URL with Copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberSurfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = subscription.url,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Скопировать URL",
                    tint = CyberCyan,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onCopyUrl() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Protocol distribution chips
            if (protocolCounts.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    protocolCounts.forEach { (proto, count) ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = CyberCyan.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "$proto: $count",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CyberCyan,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Server Count & Last Updated Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CyberGreen.copy(alpha = 0.15f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = CyberGreen,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${subscription.serverCount} узлов загружено",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberGreen
                        )
                    }
                }

                Text(
                    text = "Обновлено: ${dateFormat.format(Date(subscription.lastUpdated))}",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String) -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }

    val sampleUrl = "https://raw.githubusercontent.com/v2fly/fakedata/main/sub.txt"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberSurface,
        title = {
            Text("Добавить подписку V2Ray", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text("URL подписки, V2Ray ссылка или JSON-конфиг:", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    placeholder = { Text("https://... или vless:// или JSON", fontSize = 12.sp, color = TextSecondary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_sub_url_input"),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                TextButton(
                    onClick = {
                        urlText = sampleUrl
                        if (nameText.isBlank()) nameText = "V2Ray Demo Feed"
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Тестовая ссылка", fontSize = 12.sp, color = CyberCyan)
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text("Название (необязательно):", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    placeholder = { Text("Например: Моя подписка", fontSize = 12.sp, color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(urlText, nameText) },
                enabled = urlText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                modifier = Modifier.testTag("confirm_add_sub_btn")
            ) {
                Text("Загрузить и добавить", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = TextSecondary)
            }
        }
    )
}

