package io.github.zensu357.camswap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zensu357.camswap.ConfigManager
import kotlin.math.roundToInt

private const val KEY_ZOOM = "ghost_frame_zoom_pct"
private const val KEY_X = "ghost_frame_x_pct"
private const val KEY_Y = "ghost_frame_y_pct"
private const val KEY_MIRROR = "ghost_frame_mirror"
private const val KEY_CANVAS = "ghost_frame_canvas"

@Composable
fun StudioScreen() {
    val config = remember { ConfigManager() }
    var zoom by remember { mutableFloatStateOf(config.getInt(KEY_ZOOM, 100).toFloat()) }
    var x by remember { mutableFloatStateOf(config.getInt(KEY_X, 0).toFloat()) }
    var y by remember { mutableFloatStateOf(config.getInt(KEY_Y, 0).toFloat()) }
    var mirror by remember { mutableStateOf(config.getBoolean(KEY_MIRROR, false)) }
    var canvas by remember { mutableStateOf(config.getString(KEY_CANVAS, "720x1600")) }

    fun save() {
        config.setInt(KEY_ZOOM, zoom.roundToInt())
        config.setInt(KEY_X, x.roundToInt())
        config.setInt(KEY_Y, y.roundToInt())
        config.setBoolean(KEY_MIRROR, mirror)
        config.setString(KEY_CANVAS, canvas)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Enquadramento", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Ajuste o vídeo no GPU. A resolução final continua sendo a Surface pedida pelo app; o canvas abaixo é uma referência de composição.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        PreviewFrame(zoom = zoom, x = x, y = y, canvas = canvas)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Canvas de referência", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("720x1600", "720x1280", "1080x1920").forEach { preset ->
                        FilterChip(
                            selected = canvas == preset,
                            onClick = { canvas = preset; save() },
                            label = { Text(preset) }
                        )
                    }
                }
                Text(
                    if (canvas == "720x1600") "Preset principal: retrato 9:20, centro X=360 e olhos por volta de Y=475." else "Preset de referência $canvas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ControlSlider("Zoom", zoom, 50f..200f, "%") { zoom = it; save() }
        ControlSlider("Posição X", x, -100f..100f, "%") { x = it; save() }
        ControlSlider("Posição Y", y, -100f..100f, "%") { y = it; save() }

        Card {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Flip, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Espelhar horizontal", fontWeight = FontWeight.SemiBold)
                    Text("Útil para alinhar a composição com uma câmera frontal.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = mirror, onCheckedChange = { mirror = it; save() })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    zoom = 100f; x = 0f; y = 0f; mirror = false; canvas = "720x1600"; save()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.RestartAlt, null)
                Spacer(Modifier.width(6.dp))
                Text("RESET")
            }
            Button(
                onClick = { save() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CenterFocusStrong, null)
                Spacer(Modifier.width(6.dp))
                Text("APLICAR")
            }
        }

        Text(
            "Para garantir que todos os hooks peguem o novo enquadramento, feche e reabra o app de câmera/terceiro que estiver usando GHOSTCAM.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun ControlSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, suffix: String, onValue: (Float) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text("${value.roundToInt()}$suffix", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Slider(value = value, onValueChange = onValue, valueRange = range)
        }
    }
}

@Composable
private fun PreviewFrame(zoom: Float, x: Float, y: Float, canvas: String) {
    val ratio = when (canvas) {
        "720x1280" -> 720f / 1280f
        "1080x1920" -> 1080f / 1920f
        else -> 720f / 1600f
    }
    Box(
        modifier = Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(22.dp)).background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(18.dp)) {
            val maxH = size.height
            val frameW = maxH * ratio
            val left = (size.width - frameW) / 2f
            drawRect(Color.White.copy(alpha = 0.85f), topLeft = Offset(left, 0f), size = Size(frameW, maxH), style = Stroke(width = 3f))
            val cx = left + frameW / 2f + (x / 100f) * frameW * 0.35f
            val cy = maxH * 0.30f + (y / 100f) * maxH * 0.30f
            val headR = frameW * 0.19f * (zoom / 100f)
            drawCircle(Color(0xFFFF2028).copy(alpha = 0.35f), headR, Offset(cx, cy))
            drawLine(Color.White.copy(alpha = 0.35f), Offset(left, maxH * 0.30f), Offset(left + frameW, maxH * 0.30f), 2f)
            drawLine(Color.White.copy(alpha = 0.25f), Offset(left + frameW / 2f, 0f), Offset(left + frameW / 2f, maxH), 2f)
        }
        Text("PREVIEW DE COMPOSIÇÃO", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(26.dp))
    }
}
