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
        val version = jsonString(page, "buildVersion")
            ?: Regex("""\baVersion\s*=\s*['"](v?\d+(?:\.\d+)+(?:[-.][0-9A-Za-z.]+)?)['"]""")
                .find(page)?.groupValues?.get(1)
            ?: return null
        val notes = jsonString(page, "buildUpdateDescription") ?: htmlNotes(page)
        return PgyerUpdate(version, notes)
    }

    private fun jsonString(page: String, key: String): String? {
        val expression = Regex(""""$key"\s*:\s*("(?:\\.|[^"\\])*")""")
        val quoted = expression.find(page)?.groupValues?.get(1) ?: return null
        return runCatching { JSONObject("{\"value\":$quoted}").getString("value") }.getOrNull()
    }

    private fun htmlNotes(page: String): String {
        val content = Regex("""<div\b[^>]*class\s*=\s*["'][^"']*\bupdate-description\b[^"']*["'][^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(page)?.groupValues?.get(1) ?: return ""
        // This public-page block contains text with <br> line breaks, not executable content.
        return content.replace(Regex("(?i)<br\\s*/?>|</p\\s*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .trim()
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
