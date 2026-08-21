package io.github.zensu357.camswap

/**
 * Production wiring points.
 *
 * Keep real secrets out of the APK. BASE_URL is the public API URL of the deployed
 * GHOSTCAM backend. Square credentials live only on the backend.
 */
object GhostCamCommercialConfig {
    const val BASE_URL = ""

    val backendConfigured: Boolean
        get() = BASE_URL.startsWith("https://")
}
