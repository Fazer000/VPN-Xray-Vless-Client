package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.AppUpdateInfo
import com.example.util.DownloadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateBottomSheet(
    updateInfo: AppUpdateInfo?,
    isChecking: Boolean,
    downloadState: DownloadState,
    onCheckUpdate: () -> Unit,
    onDownloadAndUpdate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    if (isChecking) {
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
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Проверка обновлений XrayFlow",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    CircularProgressIndicator(color = CyberCyan, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Запрос к GitHub Releases...", color = TextSecondary, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
        return
    }

    if (updateInfo == null) return

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CyberCyan.copy(alpha = 0.15f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = if (updateInfo.isUpdateAvailable) "Доступно обновление XrayFlow!" else "Обновления не требуются",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = if (updateInfo.isUpdateAvailable) "Версия v${updateInfo.latestVersion}" else "У вас установлена последняя версия v${updateInfo.currentVersion}",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (updateInfo.errorMessage != null) {
                Text(
                    text = updateInfo.errorMessage,
                    color = CyberRed,
                    fontSize = 14.sp
                )
            } else if (updateInfo.isUpdateAvailable) {
                Text(
                    text = "Доступна новая версия v${updateInfo.latestVersion} (текущая: v${updateInfo.currentVersion}).",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                if (updateInfo.releaseNotes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Что нового:",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = CyberSurfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = updateInfo.releaseNotes,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (downloadState) {
                    is DownloadState.Progress -> {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Загрузка обновления...", color = TextSecondary, fontSize = 12.sp)
                                Text(
                                    text = "${downloadState.percent}%",
                                    color = CyberCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { downloadState.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = CyberCyan,
                                trackColor = CyberSurfaceVariant
                            )
                        }
                    }
                    is DownloadState.Finished -> {
                        Text(
                            text = "Загрузка завершена! Запуск установки...",
                            color = CyberGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    is DownloadState.Error -> {
                        Text(
                            text = downloadState.message,
                            color = CyberRed,
                            fontSize = 13.sp
                        )
                    }
                    DownloadState.Idle -> {}
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (updateInfo.isUpdateAvailable) {
                    TextButton(onClick = onDismiss) {
                        Text("Позже", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (downloadState !is DownloadState.Progress) {
                        Button(
                            onClick = {
                                if (updateInfo.downloadUrl.isNotBlank()) {
                                    onDownloadAndUpdate(updateInfo.downloadUrl)
                                } else {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.releaseUrl))
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (updateInfo.downloadUrl.isNotBlank()) "Скачать и обновить" else "GitHub Releases", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.releaseUrl))
                            context.startActivity(intent)
                        }
                    ) {
                        Text("GitHub", color = CyberCyan)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Понятно", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
