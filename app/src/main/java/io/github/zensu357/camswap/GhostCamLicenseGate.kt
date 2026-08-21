package io.github.zensu357.camswap

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "ghostcam_license"
private const val KEY_LICENSE = "license_key"
private const val KEY_EXPIRES = "expires_at"
private const val KEY_PLAN = "plan"
private const val KEY_DEVICE = "device_id"

private data class TestPlan(val id: String, val name: String, val price: String, val durationMs: Long)

private val plans = listOf(
    TestPlan("daily", "DIÁRIO", "US$ 8", 24L * 60L * 60L * 1000L),
    TestPlan("3days", "3 DIAS", "US$ 22", 72L * 60L * 60L * 1000L),
    TestPlan("weekly", "SEMANAL", "US$ 50", 7L * 24L * 60L * 60L * 1000L)
)

private val testKeys = mapOf(
    "GHOST-DAY-TEST" to plans[0],
    "GHOST-3DAY-TEST" to plans[1],
    "GHOST-WEEK-TEST" to plans[2]
)

@Composable
fun GhostCamLicenseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val deviceId = remember { GhostCamDevice.id(context) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var expiresAt by remember { mutableLongStateOf(prefs.getLong(KEY_EXPIRES, 0L)) }
    var plan by remember { mutableStateOf(prefs.getString(KEY_PLAN, "") ?: "") }
    var licenseKey by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val storedDevice = prefs.getString(KEY_DEVICE, null)
    val active = expiresAt > now && (storedDevice == null || storedDevice == deviceId)
    if (active) {
        content()
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GhostMark()
            Text("GHOSTCAM", fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(
                "Virtual Camera System",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AssistChip(
                onClick = {},
                label = { Text("Dispositivo: $deviceId") }
            )
            Text(
                "Cada telefone usa sua própria licença. Este ID identifica este aparelho no seu painel.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("PLANO BÁSICO", fontWeight = FontWeight.Bold)
                    plans.forEach { item ->
                        PlanRow(
                            name = item.name,
                            duration = when (item.id) {
                                "daily" -> "24 horas"
                                "3days" -> "72 horas"
                                else -> "7 dias"
                            },
                            price = item.price,
                            enabled = !busy && GhostCamCommercialConfig.backendConfigured,
                            onBuy = {
                                busy = true
                                message = "Abrindo pagamento seguro..."
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        GhostCamApi.createPurchase(context, item.id)
                                    }
                                    busy = false
                                    if (result.ok && result.checkoutUrl.startsWith("https://")) {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.checkoutUrl)))
                                        message = "Pagamento aberto no Square. Depois do pagamento, volte e ative a licença."
                                    } else {
                                        message = result.message.ifBlank { "Pagamento ainda não configurado." }
                                    }
                                }
                            }
                        )
                    }
                    if (!GhostCamCommercialConfig.backendConfigured) {
                        Text(
                            "Pagamento Square pronto para conexão. Falta somente informar a URL do backend comercial.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            OutlinedTextField(
                value = licenseKey,
                onValueChange = { licenseKey = it.uppercase().trim() },
                label = { Text("Chave de acesso") },
                placeholder = { Text("GHOST-XXXX-XXXX-XXXX") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (licenseKey.isBlank()) {
                        message = "Digite uma chave de acesso."
                        return@Button
                    }
                    busy = true
                    message = "Validando licença..."
                    scope.launch {
                        val remote = if (GhostCamCommercialConfig.backendConfigured) {
                            withContext(Dispatchers.IO) { GhostCamApi.activate(context, licenseKey) }
                        } else null

                        if (remote != null && remote.ok) {
                            prefs.edit()
                                .putString(KEY_LICENSE, remote.licenseKey)
                                .putString(KEY_PLAN, remote.plan)
                                .putLong(KEY_EXPIRES, remote.expiresAt)
                                .putString(KEY_DEVICE, deviceId)
                                .apply()
                            expiresAt = remote.expiresAt
                            plan = remote.plan
                            now = System.currentTimeMillis()
                            message = remote.message
                            busy = false
                            return@launch
                        }

                        // Test-only local fallback until the commercial backend is connected.
                        val selected = testKeys[licenseKey]
                        if (!GhostCamCommercialConfig.backendConfigured && selected != null) {
                            val activationTime = System.currentTimeMillis()
                            val newExpiry = activationTime + selected.durationMs
                            prefs.edit()
                                .putString(KEY_LICENSE, licenseKey)
                                .putString(KEY_PLAN, selected.name)
                                .putLong(KEY_EXPIRES, newExpiry)
                                .putString(KEY_DEVICE, deviceId)
                                .apply()
                            expiresAt = newExpiry
                            plan = selected.name
                            now = activationTime
                            message = "Licença de teste $plan ativada neste dispositivo."
                        } else {
                            message = remote?.message ?: "Chave inválida."
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("ATIVAR")
            }

            if (message.isNotBlank()) {
                Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }

            if (!GhostCamCommercialConfig.backendConfigured) {
                Divider()
                Text("BUILD DE TESTE", fontWeight = FontWeight.Bold)
                Text(
                    "Chaves temporárias:\nGHOST-DAY-TEST\nGHOST-3DAY-TEST\nGHOST-WEEK-TEST",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun PlanRow(name: String, duration: String, price: String, enabled: Boolean, onBuy: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Text(duration, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(price, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onBuy, enabled = enabled) { Text("COMPRAR") }
        }
    }
}

@Composable
private fun GhostMark() {
    val ghostColor = if (MaterialTheme.colorScheme.background.luminanceCompat() < 0.5f) Color.White else Color(0xFFF2F2F2)
    Canvas(modifier = Modifier.size(116.dp)) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.16f, h * 0.84f)
            lineTo(w * 0.16f, h * 0.42f)
            cubicTo(w * 0.16f, h * 0.17f, w * 0.31f, h * 0.06f, w * 0.50f, h * 0.06f)
            cubicTo(w * 0.69f, h * 0.06f, w * 0.84f, h * 0.17f, w * 0.84f, h * 0.42f)
            lineTo(w * 0.84f, h * 0.84f)
            lineTo(w * 0.72f, h * 0.73f)
            lineTo(w * 0.61f, h * 0.86f)
            lineTo(w * 0.50f, h * 0.73f)
            lineTo(w * 0.39f, h * 0.86f)
            lineTo(w * 0.28f, h * 0.73f)
            close()
        }
        drawPath(body, color = ghostColor)
        drawOval(
            Color(0xFFFF1A22),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.31f, h * 0.32f),
            size = androidx.compose.ui.geometry.Size(w * 0.15f, h * 0.09f)
        )
        drawOval(
            Color(0xFFFF1A22),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.54f, h * 0.32f),
            size = androidx.compose.ui.geometry.Size(w * 0.15f, h * 0.09f)
        )
    }
}

private fun Color.luminanceCompat(): Float = red * 0.2126f + green * 0.7152f + blue * 0.0722f
