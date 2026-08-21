package io.github.zensu357.camswap

import android.content.Context
import java.security.SecureRandom

/**
 * Stable installation identifier used only by the GHOSTCAM licensing UI/backend.
 * It is intentionally independent from Xposed/package identifiers so it cannot
 * change hook compatibility.
 */
object GhostCamDeviceIdentity {
    private const val PREFS = "ghostcam_device"
    private const val KEY_ID = "device_id"
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()

    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { if (it.isNotBlank()) return it }

        val random = SecureRandom()
        fun segment(): String = buildString(4) {
            repeat(4) { append(alphabet[random.nextInt(alphabet.size)]) }
        }

        val value = "GH-${segment()}-${segment()}"
        prefs.edit().putString(KEY_ID, value).apply()
        return value
    }
}
