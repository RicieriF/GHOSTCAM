package io.github.zensu357.camswap

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PREFS = "ghostcam_license"
private const val KEY_EXPIRES = "expires_at"
private const val KEY_PLAN = "plan"

private data class TestPlan(val name: String, val durationMs: Long)
private val testKeys = mapOf(
    "GHOST-DAY-TEST" to TestPlan("DIÁRIO", 24L * 60 * 60 * 1000),
    "GHOST-3DAY-TEST" to TestPlan("3 DIAS", 72L * 60 * 60 * 1000),
    "GHOST-WEEK-TEST" to TestPlan("SEMANAL", 7L * 24 * 60 * 60 * 1000)
)

@Composable
fun GhostCamLicenseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var expiresAt by remember { mutableLongStateOf(prefs.getLong(KEY_EXPIRES, 0L)) }
    var key by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    if (expiresAt > System.currentTimeMillis()) {
        content()
        return
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("GHOSTCAM", fontSize = 34.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text("Virtual Camera System", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("DISPOSITIVO", fontWeight = FontWeight.Bold)
                    Text(GhostCamDeviceIdentity.id(context), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.uppercase().trim() },
                label = { Text("Chave de acesso") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val plan = testKeys[key]
                    if (plan == null) {
                        message = "Chave inválida."
                    } else {
                        val expiry = System.currentTimeMillis() + plan.durationMs
                        prefs.edit().putLong(KEY_EXPIRES, expiry).putString(KEY_PLAN, plan.name).apply()
                        expiresAt = expiry
                        message = "Licença ${plan.name} ativada."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ATIVAR") }
            if (message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(message, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Build de compatibilidade: o núcleo Camera1/Camera2, IPC, renderer e metadados Xposed permanecem iguais ao original.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text("Teste: GHOST-DAY-TEST", style = MaterialTheme.typography.bodySmall)
        }
    }
}
