package io.github.zensu357.camswap

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Identidade local da instalação usada exclusivamente para vincular a licença.
 * Não usa IMEI, número de telefone, conta Google ou outro identificador sensível.
 */
object GhostCamDeviceIdentity {
    private const val PREFS = "ghostcam_device"
    private const val KEY_ID = "installation_id"
    private const val KEY_NAME = "device_name"

    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        val raw = UUID.randomUUID().toString().replace("-", "").uppercase()
        val id = "GH-${raw.take(12)}"
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }

    fun defaultName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .trim()
        .ifBlank { "Android" }

    fun name(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: defaultName()
    }

    fun setName(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, value.trim().take(80))
            .apply()
    }
}
