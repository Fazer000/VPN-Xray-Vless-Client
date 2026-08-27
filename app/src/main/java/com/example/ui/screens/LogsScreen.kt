package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.LogEntry
import com.example.util.LogLevel
import com.example.util.LogManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by LogManager.logs.collectAsState()

    var selectedFilter by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()

    val filteredLogs = remember(logs, selectedFilter, searchQuery) {
        logs.filter { entry ->
            (selectedFilter == null || entry.level == selectedFilter) &&
                    (searchQuery.isEmpty() || entry.message.contains(searchQuery, ignoreCase = true) ||
                            entry.tag.contains(searchQuery, ignoreCase = true))
        }
    }

    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            try {
                listState.scrollToItem(filteredLogs.size - 1)
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        containerColor = CyberBackground,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Core Logs & Diagnostics",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${filteredLogs.size} entries",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { autoScroll = !autoScroll },
                        modifier = Modifier.testTag("toggle_autoscroll_btn")
                    ) {
                        Icon(
                            imageVector = if (autoScroll) Icons.Default.VerticalAlignBottom else Icons.Default.Pause,
                            contentDescription = "Auto Scroll",
                            tint = if (autoScroll) CyberCyan else TextSecondary
                        )
                    }
                    IconButton(
                        onClick = {
                            val allLogsText = logs.joinToString("\n") { "[${it.timestamp}] [${it.level}] [${it.tag}] ${it.message}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Xray Logs", allLogsText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("copy_logs_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Logs",
                            tint = CyberCyan
                        )
                    }
                    IconButton(
                        onClick = { LogManager.clear() },
                        modifier = Modifier.testTag("clear_logs_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Logs",
                            tint = CyberRed
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberSurface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("logs_search_input"),
                placeholder = { Text("Filter logs...", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = CyberSurfaceVariant,
                    focusedContainerColor = CyberSurface,
                    unfocusedContainerColor = CyberSurface,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Level Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text("ALL", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
                        selectedLabelColor = CyberCyan,
                        containerColor = CyberSurface,
                        labelColor = TextSecondary
                    )
                )
                FilterChip(
                    selected = selectedFilter == LogLevel.INFO,
                    onClick = { selectedFilter = if (selectedFilter == LogLevel.INFO) null else LogLevel.INFO },
                    label = { Text("INFO", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
                        selectedLabelColor = CyberCyan,
                        containerColor = CyberSurface,
                        labelColor = TextSecondary
                    )
                )
                FilterChip(
                    selected = selectedFilter == LogLevel.WARNING,
                    onClick = { selectedFilter = if (selectedFilter == LogLevel.WARNING) null else LogLevel.WARNING },
                    label = { Text("WARN", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberAmber.copy(alpha = 0.2f),
                        selectedLabelColor = CyberAmber,
                        containerColor = CyberSurface,
                        labelColor = TextSecondary
                    )
                )
                FilterChip(
                    selected = selectedFilter == LogLevel.ERROR,
                    onClick = { selectedFilter = if (selectedFilter == LogLevel.ERROR) null else LogLevel.ERROR },
                    label = { Text("ERR", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberRed.copy(alpha = 0.2f),
                        selectedLabelColor = CyberRed,
                        containerColor = CyberSurface,
                        labelColor = TextSecondary
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Logs Terminal Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF070A10), shape = RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                if (filteredLogs.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No logs recorded yet",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Connect to a VPN server or run ping test to see activity logs.",
                            color = TextSecondary.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredLogs, key = { it.id }) { entry ->
                            LogItemRow(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogItemRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.INFO -> CyberCyan
        LogLevel.WARNING -> CyberAmber
        LogLevel.ERROR -> CyberRed
        LogLevel.DEBUG -> CyberPurple
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.timestamp,
            color = TextSecondary.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(85.dp)
        )

        Text(
            text = "[${entry.level.name.take(4)}]",
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.width(50.dp)
        )

        Text(
            text = "${entry.tag}: ",
            color = TextPrimary.copy(alpha = 0.8f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )

        Text(
            text = entry.message,
            color = if (entry.level == LogLevel.ERROR) CyberRed else TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
