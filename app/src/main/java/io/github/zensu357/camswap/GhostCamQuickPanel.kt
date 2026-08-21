package io.github.zensu357.camswap

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Painel manual de enquadramento. Não contém detecção facial, reconhecimento,
 * automação de liveness nem lógica de verificação de identidade.
 */
@Composable
fun GhostCamEntitledShell(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        content()
        FloatingActionButton(
            onClick = { open = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
                .size(48.dp),
        ) {
            Text("G", fontWeight = FontWeight.Black)
        }
    }

    if (open) {
        GhostCamLayoutEditor(onDismiss = { open = false })
    }
}

@Composable
private fun GhostCamLayoutEditor(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(GhostCamTransformStore.load(context)) }

    fun save(next: GhostCamTransformSettings) {
        settings = next.normalized()
        GhostCamTransformStore.save(context, settings)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enquadramento GHOSTCAM") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Ajuste a mídia dentro da superfície de câmera solicitada pelo aplicativo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                Text("Referência", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                PresetRow(settings) { w, h -> save(settings.copy(referenceWidth = w, referenceHeight = h)) }
                Text(
                    "${settings.referenceWidth} × ${settings.referenceHeight}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(14.dp))
                Text("Zoom ${(settings.scale * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = settings.scale,
                    onValueChange = { settings = settings.copy(scale = it) },
                    onValueChangeFinished = { save(settings) },
                    valueRange = 0.5f..2.5f,
                )

                Text("Posição X ${(settings.offsetX * 100).roundToInt()}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = settings.offsetX,
                    onValueChange = { settings = settings.copy(offsetX = it) },
                    onValueChangeFinished = { save(settings) },
                    valueRange = -1f..1f,
                )

                Text("Posição Y ${(settings.offsetY * 100).roundToInt()}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = settings.offsetY,
                    onValueChange = { settings = settings.copy(offsetY = it) },
                    onValueChangeFinished = { save(settings) },
                    valueRange = -1f..1f,
                )

                Spacer(Modifier.height(10.dp))
                Text("Ajuste", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ModeButton("Preencher", "FILL", settings.fitMode, Modifier.weight(1f)) { save(settings.copy(fitMode = it)) }
                    ModeButton("Ajustar", "FIT", settings.fitMode, Modifier.weight(1f)) { save(settings.copy(fitMode = it)) }
                    ModeButton("Esticar", "STRETCH", settings.fitMode, Modifier.weight(1f)) { save(settings.copy(fitMode = it)) }
                }

                Spacer(Modifier.height(10.dp))
                Text("Rotação", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(0, 90, 180, 270).forEach { value ->
                        val selected = settings.rotation == value
                        if (selected) {
                            Button(
                                onClick = { save(settings.copy(rotation = value)) },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                            ) { Text("$value°", style = MaterialTheme.typography.labelSmall) }
                        } else {
                            OutlinedButton(
                                onClick = { save(settings.copy(rotation = value)) },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                            ) { Text("$value°", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { save(settings.copy(mirrorX = !settings.mirrorX)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (settings.mirrorX) "Espelho horizontal: LIGADO" else "Espelho horizontal: DESLIGADO")
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        GhostCamTransformStore.reset(context)
                        settings = GhostCamTransformStore.load(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("RESTAURAR PADRÃO") }

                Spacer(Modifier.height(10.dp))
                Text(
                    "Preset recomendado para o primeiro teste: 720×1600, Preencher, zoom 100%, X 0 e Y 0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { GhostCamTransformStore.save(context, settings); onDismiss() }) {
                Text("SALVAR")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("FECHAR") } },
    )
}

@Composable
private fun PresetRow(
    current: GhostCamTransformSettings,
    onSelect: (Int, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PresetButton("720×1600", 720, 1600, current, Modifier.weight(1f), onSelect)
            PresetButton("720×1280", 720, 1280, current, Modifier.weight(1f), onSelect)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PresetButton("1080×1920", 1080, 1920, current, Modifier.weight(1f), onSelect)
            PresetButton("1080×2400", 1080, 2400, current, Modifier.weight(1f), onSelect)
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    width: Int,
    height: Int,
    current: GhostCamTransformSettings,
    modifier: Modifier,
    onSelect: (Int, Int) -> Unit,
) {
    val selected = current.referenceWidth == width && current.referenceHeight == height
    if (selected) {
        Button(onClick = { onSelect(width, height) }, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = { onSelect(width, height) }, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun ModeButton(
    label: String,
    mode: String,
    current: String,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    if (mode == current) {
        Button(
            onClick = { onSelect(mode) },
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
    } else {
        OutlinedButton(
            onClick = { onSelect(mode) },
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
    }
}
