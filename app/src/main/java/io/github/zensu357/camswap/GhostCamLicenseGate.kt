package io.github.zensu357.camswap

import android.content.Context
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
import io.github.zensu357.camswap.ui.theme.GhostCamThemeMode
import io.github.zensu357.camswap.ui.theme.GhostCamThemePrefs

private const val PREFS = "ghostcam_license"
private const val KEY_LICENSE = "license_key"
private const val KEY_EXPIRES = "expires_at"
private const val KEY_PLAN = "plan"

private data class TestPlan(val name: String, val price: String, val durationMs: Long)

private val testKeys = mapOf(
    "GHOST-DAY-TEST" to TestPlan("DIÁRIO", "US$ 8", 24L * 60L * 60L * 1000L),
    "GHOST-3DAY-TEST" to TestPlan("3 DIAS", "US$ 22", 72L * 60L * 60L * 1000L),
    "GHOST-WEEK-TEST" to TestPlan("SEMANAL", "US$ 50", 7L * 24L * 60L * 60L * 1000L)
)

@Composable
fun GhostCamLicenseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    GhostCamThemePrefs.initialize(context)
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val deviceId = remember { GhostCamDeviceIdentity.id(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var expiresAt by remember { mutableLongStateOf(prefs.getLong(KEY_EXPIRES, 0L)) }
    var plan by remember { mutableStateOf(prefs.getString(KEY_PLAN, "") ?: "") }
    var licenseKey by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val active = expiresAt > now
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            GhostMark()
            Text("GHOSTCAM", fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(
                "Virtual Camera System",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("ESTE DISPOSITIVO", fontWeight = FontWeight.Bold)
                    Text(
                        deviceId,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 20.sp
                    )
                    Text(
                        "Este ID será usado para vincular uma licença a este telefone sem alterar os identificadores internos do módulo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ThemeSelector(context)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("PLANO BÁSICO", fontWeight = FontWeight.Bold)
                    PlanRow("DIÁRIO", "24 horas", "US$ 8")
                    PlanRow("3 DIAS", "72 horas", "US$ 22")
                    PlanRow("SEMANAL", "7 dias", "US$ 50")
                    Text(
                        "Valores por dispositivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = licenseKey,
                onValueChange = { licenseKey = it.uppercase().trim() },
                label = { Text("Chave de acesso") },
                placeholder = { Text("GHOST-XXXX-XXXX-XXXX") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val selected = testKeys[licenseKey]
                    if (selected == null) {
                        message = "Chave inválida."
                    } else {
                        val activationTime = System.currentTimeMillis()
                        val newExpiry = activationTime + selected.durationMs
                        prefs.edit()
                            .putString(KEY_LICENSE, licenseKey)
                            .putString(KEY_PLAN, selected.name)
                            .putLong(KEY_EXPIRES, newExpiry)
                            .apply()
                        expiresAt = newExpiry
                        plan = selected.name
                        now = activationTime
                        message = "Licença $plan ativada."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("ATIVAR")
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    color = if (message.startsWith("Licença")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Divider()
            Text("BUILD SEGURA DE TESTE", fontWeight = FontWeight.Bold)
            Text(
                "Chaves temporárias:\nGHOST-DAY-TEST\nGHOST-3DAY-TEST\nGHOST-WEEK-TEST",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "A câmera e os hooks permanecem no núcleo original. Pagamentos e validação remota serão conectados depois dos testes de compatibilidade.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeSelector(context: Context) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("TEMA", fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeButton("Sistema", GhostCamThemeMode.SYSTEM, context, Modifier.weight(1f))
                ThemeButton("Claro", GhostCamThemeMode.LIGHT, context, Modifier.weight(1f))
                ThemeButton("Escuro", GhostCamThemeMode.DARK, context, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeButton(
    label: String,
    mode: GhostCamThemeMode,
    context: Context,
    modifier: Modifier
) {
    val selected = GhostCamThemePrefs.mode == mode
    if (selected) {
        Button(
            onClick = { GhostCamThemePrefs.set(context, mode) },
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
        ) { Text(label, fontSize = 12.sp) }
    } else {
        OutlinedButton(
            onClick = { GhostCamThemePrefs.set(context, mode) },
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
        ) { Text(label, fontSize = 12.sp) }
    }
}

@Composable
private fun PlanRow(name: String, duration: String, price: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Text(duration, style = MaterialTheme.typography.bodySmall)
        }
        Text(price, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun GhostMark() {
    val ghostColor = if (MaterialTheme.colorScheme.background.luminanceCompat() < 0.5f) Color.White else Color(0xFFF2F2F2)
    Canvas(modifier = Modifier.size(116.dp)) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.22f, h * 0.82f)
            lineTo(w * 0.22f, h * 0.43f)
            cubicTo(w * 0.22f, h * 0.18f, w * 0.38f, h * 0.08f, w * 0.50f, h * 0.08f)
            cubicTo(w * 0.68f, h * 0.08f, w * 0.80f, h * 0.24f, w * 0.80f, h * 0.46f)
            lineTo(w * 0.80f, h * 0.82f)
            lineTo(w * 0.68f, h * 0.72f)
            lineTo(w * 0.57f, h * 0.84f)
            lineTo(w * 0.47f, h * 0.72f)
            lineTo(w * 0.36f, h * 0.84f)
            close()
        }
        drawPath(body, color = ghostColor)
        drawCircle(Color.Red, radius = w * 0.055f, center = androidx.compose.ui.geometry.Offset(w * 0.41f, h * 0.40f))
        drawCircle(Color.Red, radius = w * 0.055f, center = androidx.compose.ui.geometry.Offset(w * 0.61f, h * 0.40f))
    }
}

private fun Color.luminanceCompat(): Float = red * 0.2126f + green * 0.7152f + blue * 0.0722f
