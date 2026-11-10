package com.nuvio.tv.core.tmdb

import com.nuvio.tv.BuildConfig

/**
 * TMDB credentials can be overridden from Settings. The build-time key remains
 * the fallback so existing installs keep working without configuration.
 */
object TmdbRuntimeConfig {
    private const val UNAVAILABLE_RETRY_MS = 10L * 60L * 1000L

    @Volatile
    var apiKey: String = BuildConfig.TMDB_API_KEY

    @Volatile
    private var apiUnavailableUntilMs: Long = 0L

    fun effectiveApiKey(): String =
        apiKey.trim().ifBlank { BuildConfig.TMDB_API_KEY.trim() }

    fun isApiReachable(): Boolean = System.currentTimeMillis() >= apiUnavailableUntilMs

    fun markApiUnavailable() {
        apiUnavailableUntilMs = System.currentTimeMillis() + UNAVAILABLE_RETRY_MS
    }

    /** Allows a manual search to immediately retry after a key or network change. */
    fun markApiAvailable() {
        apiUnavailableUntilMs = 0L
    }
}
