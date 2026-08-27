package com.example.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Subscription
import com.example.ui.theme.*
import com.example.ui.viewmodel.VpnViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SubscriptionsScreen(
    viewModel: VpnViewModel
) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val isSubLoading by viewModel.isSubLoading.collectAsState()
    val subStateMessage by viewModel.subStateMessage.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(subStateMessage) {
        subStateMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSubStateMessage()
        }
    }

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
                Icon(Icons.Default.Add, contentDescription = "Add Subscription")
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
                        text = "Subscriptions",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "V2Ray / V2RayTun URL Feeds",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                if (isSubLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = CyberCyan,
                        strokeWidth = 2.dp
                    )
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
                        Icon(
                            imageVector = Icons.Default.RssFeed,
                            contentDescription = "No Subscriptions",
                            tint = TextSecondary,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Subscriptions Added",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap + to add a V2RayTun subscription link",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Add Subscription Link", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(subscriptions, key = { it.id }) { sub ->
                        SubscriptionCard(
                            subscription = sub,
                            isSubLoading = isSubLoading,
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
            onAdd = { url, name, userAgent ->
                viewModel.addSubscription(url, name, userAgent)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun SubscriptionCard(
    subscription: Subscription,
    isSubLoading: Boolean,
    onUpdate: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sub_card_${subscription.id}"),
        shape = RoundedCornerShape(14.dp),
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
                        color = TextPrimary
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
                            contentDescription = "Refresh",
                            tint = CyberCyan
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_sub_btn_${subscription.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = CyberRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = subscription.url,
                fontSize = 11.sp,
                color = TextSecondary,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CyberSurfaceVariant
                ) {
                    Text(
                        text = "${subscription.serverCount} servers imported",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CyberGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "Updated: ${dateFormat.format(Date(subscription.lastUpdated))}",
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
    onAdd: (url: String, name: String, userAgent: String) -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }

    val userAgentOptions = listOf(
        "Auto (Happ / v2rayTun / v2rayNG)" to "",
        "v2rayNG" to "v2rayNG/1.8.19 (Android; com.v2ray.ang)",
        "Happ" to "Happ/1.2.0 (Android; com.happ.vpn)",
        "v2rayTun" to "v2rayTun/1.5.8 (Android; com.v2raytun.android)",
        "Clash" to "ClashforWindows/0.20.39"
    )
    var selectedUaIndex by remember { mutableIntStateOf(0) }

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

                Spacer(modifier = Modifier.height(12.dp))

                Text("Эмуляция клиента (User-Agent):", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    userAgentOptions.forEachIndexed { index, (label, _) ->
                        FilterChip(
                            selected = selectedUaIndex == index,
                            onClick = { selectedUaIndex = index },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberCyan,
                                selectedLabelColor = Color.Black,
                                containerColor = CyberSurfaceVariant,
                                labelColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(urlText, nameText, userAgentOptions[selectedUaIndex].second) },
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
