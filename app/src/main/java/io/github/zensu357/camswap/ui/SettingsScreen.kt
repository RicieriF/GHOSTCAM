package io.github.zensu357.camswap.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zensu357.camswap.BuildConfig
import io.github.zensu357.camswap.ConfigManager
import io.github.zensu357.camswap.GhostCamApi
import io.github.zensu357.camswap.GhostCamCommercialConfig
import io.github.zensu357.camswap.GhostCamDevice
import io.github.zensu357.camswap.R
import io.github.zensu357.camswap.ui.theme.GhostCamThemePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var themeMode by remember { mutableStateOf(GhostCamThemePrefs.get(context)) }
    var reportPackage by remember { mutableStateOf("") }
    var reportNote by remember { mutableStateOf("") }
    var reportMessage by remember { mutableStateOf("") }
    var streamUrl by remember(uiState.streamUrl) { mutableStateOf(uiState.streamUrl) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Section("APARÊNCIA") {
            Text("Tema GHOSTCAM", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    GhostCamThemePrefs.SYSTEM to "Sistema",
                    GhostCamThemePrefs.DARK to "Dark",
                    GhostCamThemePrefs.LIGHT to "White"
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = {
                            themeMode = mode
                            GhostCamThemePrefs.set(context, mode)
                            (context as? Activity)?.recreate()
                        },
                        label = { Text(label) }
                    )
                }
            }
        }

        Section("CONTROLES") {
            SwitchRow(Icons.Default.NotificationsActive, "Controle por notificação", uiState.notificationControlEnabled) {
                viewModel.setNotificationControlEnabled(it)
                val intent = Intent(context, io.github.zensu357.camswap.NotificationService::class.java)
                if (it) context.startForegroundService(intent) else context.stopService(intent)
            }
            Divider()
            SwitchRow(Icons.Default.Videocam, "Controle flutuante", uiState.overlayControlEnabled) { enabled ->
                val intent = Intent(context, io.github.zensu357.camswap.OverlayControlService::class.java)
                if (enabled && !Settings.canDrawOverlays(context)) {
                    viewModel.setOverlayControlEnabled(true)
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else {
                    viewModel.setOverlayControlEnabled(enabled)
                    if (enabled) context.startService(intent) else context.stopService(intent)
                }
            }
            Divider()
            SwitchRow(Icons.Default.VolumeUp, "Áudio do vídeo", uiState.playVideoSound) { viewModel.setPlayVideoSound(it) }
            Divider()
            SwitchRow(Icons.Default.Mic, "Hook do microfone", uiState.enableMicHook) { viewModel.setEnableMicHook(it) }
            Divider()
            SwitchRow(Icons.Default.Shuffle, "Reprodução aleatória", uiState.enableRandomPlay) { viewModel.setEnableRandomPlay(it) }
        }

        Section("FONTE DE VÍDEO") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.mediaSourceType == ConfigManager.MEDIA_SOURCE_LOCAL,
                    onClick = { viewModel.setMediaSourceType(ConfigManager.MEDIA_SOURCE_LOCAL) },
                    label = { Text("Local") }
                )
                FilterChip(
                    selected = uiState.mediaSourceType == ConfigManager.MEDIA_SOURCE_STREAM,
                    onClick = { viewModel.setMediaSourceType(ConfigManager.MEDIA_SOURCE_STREAM) },
                    label = { Text("Stream") }
                )
            }
            AnimatedVisibility(uiState.mediaSourceType == ConfigManager.MEDIA_SOURCE_STREAM) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = streamUrl,
                        onValueChange = { streamUrl = it },
                        label = { Text("URL do stream") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { viewModel.setStreamUrl(streamUrl) }, modifier = Modifier.align(Alignment.End)) {
                        Text("SALVAR")
                    }
                }
            }
        }

        Section("AVANÇADO") {
            SwitchRow(Icons.Outlined.FolderSpecial, "Forçar diretório privado", uiState.forcePrivateDir) { viewModel.setForcePrivateDir(it) }
            Divider()
            SwitchRow(Icons.Outlined.NotificationsOff, "Ocultar avisos Toast", uiState.disableToast) { viewModel.setDisableToast(it) }
            Divider()
            SwitchRow(Icons.Default.Image, "Substituição de foto", uiState.enablePhotoFake) { viewModel.setEnablePhotoFake(it) }
            Divider()
            ClickRow(Icons.Default.Security, "Permissões do sistema") {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                })
            }
            Divider()
            ClickRow(Icons.Default.Language, "Idioma") {
                val next = if (io.github.zensu357.camswap.utils.LocaleHelper.getLanguage(context) == "en") "" else "en"
                viewModel.setLanguage(context, next)
            }
        }

        Section("DISPOSITIVO E LICENÇA") {
            InfoLine("ID do dispositivo", GhostCamDevice.id(context))
            InfoLine("Versão", BuildConfig.VERSION_NAME)
            Text(
                "Uma licença é vinculada a um único ID de dispositivo. Trocas de aparelho são liberadas pelo painel administrativo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("RELATÓRIO DE COMPATIBILIDADE") {
            Text(
                "Se um aplicativo atualizar e apresentar problema, envie um relatório técnico simples. Nenhum conteúdo de câmera é enviado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = reportPackage,
                onValueChange = { reportPackage = it },
                label = { Text("Pacote/app afetado") },
                placeholder = { Text("ex.: com.exemplo.app") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = reportNote,
                onValueChange = { reportNote = it },
                label = { Text("O que aconteceu") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Button(
                onClick = {
                    if (!GhostCamCommercialConfig.backendConfigured) {
                        reportMessage = "Backend ainda não conectado."
                    } else {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                GhostCamApi.sendCompatibilityReport(context, reportPackage, reportNote)
                            }
                            reportMessage = if (ok) "Relatório enviado." else "Falha ao enviar relatório."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ENVIAR RELATÓRIO") }
            if (reportMessage.isNotBlank()) Text(reportMessage, style = MaterialTheme.typography.bodySmall)
        }

        Section("SOBRE") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Default.Info, null)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("GHOSTCAM", fontWeight = FontWeight.Bold)
                    Text("Virtual Camera System", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Interface GHOSTCAM. Sem links promocionais ou atalhos para o repositório upstream.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(icon, null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClickRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(icon, null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
