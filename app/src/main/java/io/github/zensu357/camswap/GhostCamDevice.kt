package io.github.zensu357.camswap

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/**
 * Stable app-scoped device identifier.
 *
 * This deliberately does not use IMEI/serial/advertising identifiers. The value is
 * generated once per installation profile and stored in app-private preferences.
 * It is suitable for binding one GHOSTCAM license to one phone installation.
 */
object GhostCamDevice {
    private const val PREFS = "ghostcam_device"
    private const val KEY_ID = "device_id"

    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val raw = UUID.randomUUID().toString().replace("-", "")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .uppercase()
        val id = "GH-${digest.take(4)}-${digest.substring(4, 8)}"
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }
}
