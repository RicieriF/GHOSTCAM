package io.github.zensu357.camswap

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Configuração visual da mídia. Os valores são manuais e genéricos; não fazem
 * detecção de rosto, identidade, liveness ou automação de verificação.
 */
data class GhostCamTransformSettings(
    val scale: Float = 1.0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Int = 0,
    val mirrorX: Boolean = false,
    val fitMode: String = "FILL",
    val background: String = "#000000",
    val referenceWidth: Int = 720,
    val referenceHeight: Int = 1600,
) {
    fun normalized(): GhostCamTransformSettings = copy(
        scale = scale.coerceIn(0.25f, 4f),
        offsetX = offsetX.coerceIn(-1f, 1f),
        offsetY = offsetY.coerceIn(-1f, 1f),
        rotation = ((rotation % 360) + 360) % 360,
        fitMode = fitMode.takeIf { it in setOf("FIT", "FILL", "STRETCH") } ?: "FILL",
        referenceWidth = referenceWidth.coerceIn(240, 4320),
        referenceHeight = referenceHeight.coerceIn(240, 4320),
    )
}

object GhostCamTransformStore {
    private const val PREFS = "ghostcam_transform"
    private const val FILE_NAME = "ghostcam_transform.json"

    fun load(context: Context): GhostCamTransformSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return GhostCamTransformSettings(
            scale = p.getFloat("scale", 1f),
            offsetX = p.getFloat("offset_x", 0f),
            offsetY = p.getFloat("offset_y", 0f),
            rotation = p.getInt("rotation", 0),
            mirrorX = p.getBoolean("mirror_x", false),
            fitMode = p.getString("fit_mode", "FILL") ?: "FILL",
            background = p.getString("background", "#000000") ?: "#000000",
            referenceWidth = p.getInt("reference_width", 720),
            referenceHeight = p.getInt("reference_height", 1600),
        ).normalized()
    }

    fun save(context: Context, settings: GhostCamTransformSettings) {
        val s = settings.normalized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("scale", s.scale)
            .putFloat("offset_x", s.offsetX)
            .putFloat("offset_y", s.offsetY)
            .putInt("rotation", s.rotation)
            .putBoolean("mirror_x", s.mirrorX)
            .putString("fit_mode", s.fitMode)
            .putString("background", s.background)
            .putInt("reference_width", s.referenceWidth)
            .putInt("reference_height", s.referenceHeight)
            .apply()

        // Espelho legível pelo processo do módulo. A escrita externa é best-effort:
        // em aparelhos onde o acesso não estiver concedido, o app continua mantendo
        // a configuração privada e informa isso na interface.
        runCatching {
            val dir = File(ConfigManager.DEFAULT_CONFIG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE_NAME)
            val json = JSONObject().apply {
                put("version", 1)
                put("scale", s.scale.toDouble())
                put("offsetX", s.offsetX.toDouble())
                put("offsetY", s.offsetY.toDouble())
                put("rotation", s.rotation)
                put("mirrorX", s.mirrorX)
                put("fitMode", s.fitMode)
                put("background", s.background)
                put("referenceWidth", s.referenceWidth)
                put("referenceHeight", s.referenceHeight)
            }
            file.writeText(json.toString())
        }
    }

    fun reset(context: Context) = save(context, GhostCamTransformSettings())
}
