package com.example.ui.components

import android.content.Intent
import android.net.Uri
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

@Composable
fun UpdateDialog(
    updateInfo: AppUpdateInfo?,
    isChecking: Boolean,
    downloadState: DownloadState,
    onCheckUpdate: () -> Unit,
    onDownloadAndUpdate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    if (isChecking) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = CyberSurface,
            title = {
                Text("Проверка обновлений", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    CircularProgressIndicator(color = CyberCyan, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Запрос к GitHub Releases...", color = TextSecondary, fontSize = 14.sp)
                }
            },
            confirmButton = {}
        )
        return
    }

    if (updateInfo == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (updateInfo.isUpdateAvailable) "Доступно обновление!" else "Проверка обновлений",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (updateInfo.errorMessage != null) {
                    Text(
                        text = updateInfo.errorMessage,
                        color = CyberRed,
                        fontSize = 14.sp
                    )
                } else if (updateInfo.isUpdateAvailable) {
                    Text(
                        text = "Версия ${updateInfo.latestVersion} доступна для установки (у вас v${updateInfo.currentVersion}).",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    if (updateInfo.releaseNotes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Что нового:",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = CyberSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = updateInfo.releaseNotes,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    when (downloadState) {
                        is DownloadState.Progress -> {
                            Column {
                                Text(
                                    text = "Загрузка: ${downloadState.percent}%",
                                    color = CyberCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
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
                } else {
                    Text(
                        text = "У вас установлена актуальная версия приложения (v${updateInfo.currentVersion}).",
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            if (updateInfo.isUpdateAvailable && downloadState !is DownloadState.Progress) {
                Button(
                    onClick = {
                        if (updateInfo.downloadUrl.isNotBlank()) {
                            onDownloadAndUpdate(updateInfo.downloadUrl)
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.releaseUrl))
                            context.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black)
                ) {
                    Text(if (updateInfo.downloadUrl.isNotBlank()) "Скачать и обновить" else "Открыть GitHub", fontWeight = FontWeight.Bold)
                }
            } else if (!updateInfo.isUpdateAvailable) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black)
                ) {
                    Text("Отлично", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (updateInfo.isUpdateAvailable) {
                TextButton(onClick = onDismiss) {
                    Text("Позже", color = TextSecondary)
                }
            } else {
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.releaseUrl))
                        context.startActivity(intent)
                    }
                ) {
                    Text("GitHub Releases", color = CyberCyan)
                }
            }
        }
    )
}
