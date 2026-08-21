package io.github.zensu357.camswap

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zensu357.camswap.ui.theme.GhostCamThemeController
import io.github.zensu357.camswap.ui.theme.GhostCamThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

private data class TestPlan(
    val code: String,
    val label: String,
    val price: String,
    val hours: Long,
    val testKey: String,
)

private val ghostPlans = listOf(
    TestPlan("DAY", "DIÁRIO", "US$8", 24, "GHOST-DAY-TEST"),
    TestPlan("THREE_DAY", "3 DIAS", "US$22", 72, "GHOST-3DAY-TEST"),
    TestPlan("WEEK", "SEMANAL", "US$50", 168, "GHOST-WEEK-TEST"),
)

private const val LICENSE_PREFS = "ghostcam_license"
private const val KEY_LICENSE = "license_key"
private const val KEY_PLAN = "plan"
private const val KEY_EXPIRES = "expires_epoch_ms"
private const val KEY_REMOTE = "remote_license"
private const val KEY_LAST_VERIFIED = "last_verified_epoch_ms"
private const val KEY_PENDING_CHECKOUT = "pending_checkout"
private const val OFFLINE_GRACE_MS = 6L * 60 * 60 * 1000

@Composable
fun GhostCamLicenseGate(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(LICENSE_PREFS, Context.MODE_PRIVATE) }
    val api = remember { GhostCamApi(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val deviceId = remember { GhostCamDeviceIdentity.id(context) }

    val initialLicense = remember { prefs.getString(KEY_LICENSE, "") ?: "" }
    val initialExpiry = remember { prefs.getLong(KEY_EXPIRES, 0L) }
    val initialRemote = remember { prefs.getBoolean(KEY_REMOTE, false) }
    val initialVerified = remember { prefs.getLong(KEY_LAST_VERIFIED, 0L) }
    val now = System.currentTimeMillis()

    var entitled by remember {
        mutableStateOf(
            initialExpiry > now && (!initialRemote || (now - initialVerified) <= OFFLINE_GRACE_MS)
        )
    }
    var checking by remember { mutableStateOf(initialRemote && initialLicense.isNotBlank() && api.configured) }
    var licenseInput by remember { mutableStateOf(initialLicense) }
    var message by remember { mutableStateOf("") }
    var pendingCheckout by remember { mutableStateOf(prefs.getString(KEY_PENDING_CHECKOUT, "") ?: "") }
    var buyingPlan by remember { mutableStateOf<String?>(null) }

    fun saveRemote(key: String, plan: String, expiresAt: String?) {
        val expiry = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrDefault(0L)
        prefs.edit()
            .putString(KEY_LICENSE, key)
            .putString(KEY_PLAN, plan)
            .putLong(KEY_EXPIRES, expiry)
            .putBoolean(KEY_REMOTE, true)
            .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
            .apply()
        entitled = expiry > System.currentTimeMillis()
    }

    fun activateKey(key: String) {
        val normalized = key.trim().uppercase()
        val testPlan = ghostPlans.firstOrNull { it.testKey == normalized }
        if (testPlan != null) {
            val expiry = System.currentTimeMillis() + testPlan.hours * 60 * 60 * 1000
            prefs.edit()
                .putString(KEY_LICENSE, normalized)
                .putString(KEY_PLAN, testPlan.code)
                .putLong(KEY_EXPIRES, expiry)
                .putBoolean(KEY_REMOTE, false)
                .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
                .apply()
            message = "Licença de teste ${testPlan.label} ativada."
            entitled = true
            return
        }

        if (!api.configured) {
            message = "Backend ainda não configurado. Nesta build use uma chave de teste."
            return
        }

        checking = true
        message = "Validando licença…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { api.activate(normalized) }
            checking = false
            if (result.ok && result.json.optBoolean("active", false)) {
                saveRemote(
                    normalized,
                    result.json.optString("plan", ""),
                    result.json.optString("expiresAt", null),
                )
                message = "Licença ativada neste dispositivo."
            } else {
                message = result.error ?: "Não foi possível ativar esta licença."
            }
        }
    }

    fun startCheckout(plan: TestPlan) {
        if (!api.configured) {
            message = "Configure a URL do backend para habilitar pagamentos Square."
            return
        }
        buyingPlan = plan.code
        message = "Criando checkout seguro…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { api.checkout(plan.code) }
            buyingPlan = null
            if (result.ok) {
                val checkoutId = result.json.optString("checkoutId")
                val url = result.json.optString("url")
                if (checkoutId.isNotBlank() && url.startsWith("https://")) {
                    pendingCheckout = checkoutId
                    prefs.edit().putString(KEY_PENDING_CHECKOUT, checkoutId).apply()
                    message = "Pagamento aberto no Square. Depois volte e verifique."
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }.onFailure {
                        message = "Checkout criado, mas não foi possível abrir o navegador."
                    }
                } else {
                    message = "Resposta de checkout inválida."
                }
            } else {
                message = result.error ?: "Falha ao criar checkout."
            }
        }
    }

    fun verifyCheckout() {
        if (pendingCheckout.isBlank() || !api.configured) return
        checking = true
        message = "Verificando pagamento…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { api.checkoutStatus(pendingCheckout) }
            checking = false
            if (!result.ok) {
                message = result.error ?: "Não foi possível verificar o pagamento."
                return@launch
            }
            when (result.json.optString("status")) {
                "PAID" -> {
                    val key = result.json.optString("licenseKey")
                    if (key.isBlank()) {
                        message = "Pagamento confirmado, aguardando emissão da licença."
                    } else {
                        prefs.edit().remove(KEY_PENDING_CHECKOUT).apply()
                        pendingCheckout = ""
                        licenseInput = key
                        activateKey(key)
                    }
                }
                "PENDING" -> message = "Pagamento ainda não confirmado pelo Square."
                else -> message = "Checkout: ${result.json.optString("status", "desconhecido")}."
            }
        }
    }

    LaunchedEffect(Unit) {
        if (initialRemote && initialLicense.isNotBlank() && api.configured) {
            val result = withContext(Dispatchers.IO) { api.status(initialLicense) }
            checking = false
            if (result.ok && result.json.optBoolean("active", false)) {
                saveRemote(
                    initialLicense,
                    result.json.optString("plan", ""),
                    result.json.optString("expiresAt", null),
                )
            } else if (initialExpiry <= System.currentTimeMillis() ||
                (System.currentTimeMillis() - initialVerified) > OFFLINE_GRACE_MS
            ) {
                entitled = false
                message = result.error ?: "Sua licença precisa ser validada novamente."
            }
        } else {
            checking = false
        }
    }

    if (entitled) {
        content()
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            GhostMark(Modifier.size(98.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = "GHOSTCAM",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "Virtual Camera System",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Este dispositivo", fontWeight = FontWeight.Bold)
                    Text(deviceId, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cada licença fica vinculada a um único dispositivo. Se trocar de aparelho, o administrador pode liberar a transferência.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            ThemeSelector(context)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = licenseInput,
                onValueChange = { licenseInput = it.uppercase() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Chave de licença") },
                placeholder = { Text("GHOST-XXXX-XXXX-XXXX") },
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { activateKey(licenseInput) },
                enabled = !checking && licenseInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("ATIVAR")
                }
            }

            if (message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    message,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(18.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            Text("Comprar licença", fontWeight = FontWeight.Bold)
            Text(
                if (api.configured) "Pagamento processado pelo Square" else "Square será habilitado quando o backend for conectado",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            ghostPlans.forEach { plan ->
                PlanCard(
                    plan = plan,
                    loading = buyingPlan == plan.code,
                    enabled = buyingPlan == null && !checking,
                    onBuy = { startCheckout(plan) },
                )
                Spacer(Modifier.height(8.dp))
            }

            if (pendingCheckout.isNotBlank()) {
                OutlinedButton(
                    onClick = { verifyCheckout() },
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("VERIFICAR PAGAMENTO")
                }
                Spacer(Modifier.height(8.dp))
            }

            if (!api.configured) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("BUILD DE TESTE V0.1", fontWeight = FontWeight.Bold)
                        Text(
                            "Chaves locais: GHOST-DAY-TEST, GHOST-3DAY-TEST e GHOST-WEEK-TEST. " +
                                "Na versão conectada, a validação será pelo servidor e por dispositivo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ThemeSelector(context: Context) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Tema", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ThemeButton("Sistema", GhostCamThemeMode.SYSTEM, context, Modifier.weight(1f))
            ThemeButton("Claro", GhostCamThemeMode.LIGHT, context, Modifier.weight(1f))
            ThemeButton("Escuro", GhostCamThemeMode.DARK, context, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ThemeButton(
    label: String,
    mode: GhostCamThemeMode,
    context: Context,
    modifier: Modifier,
) {
    val selected = GhostCamThemeController.mode == mode
    if (selected) {
        Button(
            onClick = { GhostCamThemeController.set(context, mode) },
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
    } else {
        OutlinedButton(
            onClick = { GhostCamThemeController.set(context, mode) },
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun PlanCard(plan: TestPlan, loading: Boolean, enabled: Boolean, onBuy: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(plan.label, fontWeight = FontWeight.Bold)
                Text(
                    when (plan.code) {
                        "DAY" -> "24 horas"
                        "THREE_DAY" -> "72 horas"
                        else -> "7 dias"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(plan.price, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(10.dp))
            OutlinedButton(onClick = onBuy, enabled = enabled) {
                Text(if (loading) "…" else "COMPRAR")
            }
        }
    }
}

@Composable
private fun GhostMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.76f)
            lineTo(w * 0.18f, h * 0.43f)
            cubicTo(w * 0.18f, h * 0.18f, w * 0.34f, h * 0.08f, w * 0.50f, h * 0.08f)
            cubicTo(w * 0.66f, h * 0.08f, w * 0.82f, h * 0.18f, w * 0.82f, h * 0.43f)
            lineTo(w * 0.82f, h * 0.78f)
            cubicTo(w * 0.75f, h * 0.70f, w * 0.70f, h * 0.70f, w * 0.64f, h * 0.82f)
            cubicTo(w * 0.58f, h * 0.70f, w * 0.53f, h * 0.70f, w * 0.47f, h * 0.82f)
            cubicTo(w * 0.41f, h * 0.70f, w * 0.36f, h * 0.70f, w * 0.30f, h * 0.82f)
            cubicTo(w * 0.25f, h * 0.72f, w * 0.22f, h * 0.72f, w * 0.18f, h * 0.76f)
            close()
        }
        drawPath(path, color = MaterialTheme.colorScheme.onBackground)
        drawOval(
            color = Color(0x66FF0000),
            topLeft = Offset(w * 0.30f, h * 0.37f),
            size = androidx.compose.ui.geometry.Size(w * 0.17f, h * 0.12f),
        )
        drawOval(
            color = Color(0x66FF0000),
            topLeft = Offset(w * 0.53f, h * 0.37f),
            size = androidx.compose.ui.geometry.Size(w * 0.17f, h * 0.12f),
        )
        drawOval(
            color = Color(0xFFFF202A),
            topLeft = Offset(w * 0.34f, h * 0.40f),
            size = androidx.compose.ui.geometry.Size(w * 0.09f, h * 0.06f),
        )
        drawOval(
            color = Color(0xFFFF202A),
            topLeft = Offset(w * 0.57f, h * 0.40f),
            size = androidx.compose.ui.geometry.Size(w * 0.09f, h * 0.06f),
        )
    }
}
