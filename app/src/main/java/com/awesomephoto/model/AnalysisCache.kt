package com.awesomephoto.model

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.awesomephoto.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Persistent, versioned cache. A changed source file automatically misses. */
class AnalysisCache(context: Context, modelIdentity: String) : SQLiteOpenHelper(context, "photo_analysis_cache.db", null, 2) {
    companion object { val ALGORITHM_VERSION = "${WallpaperAssessment.RULE_VERSION}:${BuildConfig.ANALYSIS_FINGERPRINT}" }

    private val version = "$ALGORITHM_VERSION:$modelIdentity"

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE analysis_cache (
            algorithm_version TEXT NOT NULL, source_uri TEXT NOT NULL, byte_size INTEGER NOT NULL,
            modified_at INTEGER NOT NULL, score INTEGER NOT NULL, kind TEXT NOT NULL, labels TEXT NOT NULL, details_json TEXT,
            PRIMARY KEY (algorithm_version, source_uri, byte_size, modified_at))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE analysis_cache ADD COLUMN details_json TEXT")
    }

    fun get(draft: PhotoCandidate, byteSize: Long, modifiedAt: Long): PhotoCandidate? {
        readableDatabase.rawQuery(
            "SELECT score, kind, labels, details_json FROM analysis_cache WHERE algorithm_version=? AND source_uri=? AND byte_size=? AND modified_at=?",
            arrayOf(version, draft.uri.toString(), byteSize.toString(), modifiedAt.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            // Legacy entries have no measured breakdown: reanalyse instead of inventing reasons.
            if (cursor.isNull(3)) return null
            val details = runCatching {
                val rows = JSONArray(cursor.getString(3))
                List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    ScoreDetail(row.getString("name"), row.getDouble("weight"), row.getDouble("value").toFloat(), row.getString("policy"), row.getString("reason"))
                }.also { require(WallpaperAssessment.valid(it)) }
            }.getOrNull() ?: return null
            val labels = cursor.getString(2).takeIf(String::isNotEmpty)?.split('\u001f') ?: emptyList()
            return draft.copy(score = PhotoScorer.Result(details).total, kind = PhotoKind.valueOf(cursor.getString(1)), semanticLabels = labels, scoreDetails = details)
        }
    }

    fun put(candidate: PhotoCandidate, byteSize: Long, modifiedAt: Long) {
        require(WallpaperAssessment.valid(candidate.scoreDetails)) { "不缓存不完整评分" }
        val details = JSONArray()
        candidate.scoreDetails.forEach { detail ->
            details.put(JSONObject().put("name", detail.name).put("weight", detail.weight)
                .put("value", detail.value).put("policy", detail.policy).put("reason", detail.reason))
        }
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO analysis_cache (algorithm_version, source_uri, byte_size, modified_at, score, kind, labels, details_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(version, candidate.uri.toString(), byteSize, modifiedAt, candidate.score, candidate.kind.name, candidate.semanticLabels.joinToString("\u001f"), details.toString())
        )
    }
}
