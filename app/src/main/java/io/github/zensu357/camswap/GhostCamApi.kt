package io.github.zensu357.camswap

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GhostCamApi {
    data class ActivationResult(
        val ok: Boolean,
        val message: String,
        val plan: String = "",
        val expiresAt: Long = 0L,
        val licenseKey: String = ""
    )

    data class PurchaseResult(
        val ok: Boolean,
        val checkoutUrl: String = "",
        val purchaseId: String = "",
        val message: String = ""
    )

    fun activate(context: Context, licenseKey: String): ActivationResult {
        if (!GhostCamCommercialConfig.backendConfigured) {
            return ActivationResult(false, "Servidor comercial ainda não configurado.")
        }
        return try {
            val body = JSONObject()
                .put("license_key", licenseKey.trim().uppercase())
                .put("device_id", GhostCamDevice.id(context))
                .put("app_version", BuildConfig.VERSION_NAME)
            val json = post("/api/license/activate", body)
            ActivationResult(
                ok = json.optBoolean("ok", false),
                message = json.optString("message", if (json.optBoolean("ok", false)) "Licença ativada." else "Falha na ativação."),
                plan = json.optString("plan", ""),
                expiresAt = json.optLong("expires_at", 0L),
                licenseKey = json.optString("license_key", licenseKey.trim().uppercase())
            )
        } catch (t: Throwable) {
            ActivationResult(false, "Não foi possível conectar ao servidor: ${t.message ?: "erro de rede"}")
        }
    }

    fun createPurchase(context: Context, plan: String): PurchaseResult {
        if (!GhostCamCommercialConfig.backendConfigured) {
            return PurchaseResult(false, message = "Square/backend ainda não configurado.")
        }
        return try {
            val body = JSONObject()
                .put("plan", plan)
                .put("device_id", GhostCamDevice.id(context))
                .put("app_version", BuildConfig.VERSION_NAME)
            val json = post("/api/purchase/create", body)
            PurchaseResult(
                ok = json.optBoolean("ok", false),
                checkoutUrl = json.optString("checkout_url", ""),
                purchaseId = json.optString("purchase_id", ""),
                message = json.optString("message", "")
            )
        } catch (t: Throwable) {
            PurchaseResult(false, message = "Não foi possível criar o pagamento: ${t.message ?: "erro de rede"}")
        }
    }

    fun sendCompatibilityReport(context: Context, targetPackage: String, note: String): Boolean {
        if (!GhostCamCommercialConfig.backendConfigured) return false
        return try {
            val body = JSONObject()
                .put("device_id", GhostCamDevice.id(context))
                .put("target_package", targetPackage.trim())
                .put("note", note.trim())
                .put("app_version", BuildConfig.VERSION_NAME)
            post("/api/report", body).optBoolean("ok", false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val connection = (URL(GhostCamCommercialConfig.BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val input = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = input?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (text.isBlank()) return JSONObject().put("ok", status in 200..299)
        return JSONObject(text)
    }
}
