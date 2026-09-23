package com.awesomephoto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PgyerUpdate(val version: String, val notes: String)

/** Checks only Pgyer's public install page; API keys never enter the APK. */
object PgyerUpdateChecker {
    suspend fun fetch(pageUrl: String): PgyerUpdate? = withContext(Dispatchers.IO) {
        if (pageUrl.isBlank()) return@withContext null
        runCatching {
            val connection = URL(pageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", "AwesomePhoto/${BuildConfig.VERSION_NAME}")
            connection.inputStream.bufferedReader().use { page -> parse(page.readText()) }
        }.getOrNull()
    }

    internal fun parse(page: String): PgyerUpdate? {
        val normalized = page.replace("\\\\\"", "\"")
        val version = jsonString(normalized, "buildVersion") ?: return null
        return PgyerUpdate(version, jsonString(normalized, "buildUpdateDescription").orEmpty())
    }

    private fun jsonString(page: String, key: String): String? {
        val expression = Regex("\\\"$key\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
        val raw = expression.find(page)?.groupValues?.get(1) ?: return null
        return runCatching { JSONObject("{\\\"value\\\":\\\"$raw\\\"}").getString("value") }.getOrElse { raw }
    }

    internal fun isNewer(candidate: String, installed: String): Boolean {
        val candidateParts = candidate.removePrefix("v").split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val installedParts = installed.removePrefix("v").split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(candidateParts.size, installedParts.size)) {
            val difference = (candidateParts.getOrElse(index) { 0 }).compareTo(installedParts.getOrElse(index) { 0 })
            if (difference != 0) return difference > 0
        }
        return false
    }
}
