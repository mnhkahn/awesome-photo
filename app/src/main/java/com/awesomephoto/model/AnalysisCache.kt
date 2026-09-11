package com.awesomephoto.model

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Persistent, versioned cache. A changed source file automatically misses. */
class AnalysisCache(context: Context) : SQLiteOpenHelper(context, "photo_analysis_cache.db", null, 1) {
    companion object { const val ALGORITHM_VERSION = "segformer-b0-ade20k-score-v1" }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE analysis_cache (
            algorithm_version TEXT NOT NULL, source_uri TEXT NOT NULL, byte_size INTEGER NOT NULL,
            modified_at INTEGER NOT NULL, score INTEGER NOT NULL, kind TEXT NOT NULL, labels TEXT NOT NULL,
            PRIMARY KEY (algorithm_version, source_uri, byte_size, modified_at))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun get(draft: PhotoCandidate, byteSize: Long, modifiedAt: Long): PhotoCandidate? {
        readableDatabase.rawQuery(
            "SELECT score, kind, labels FROM analysis_cache WHERE algorithm_version=? AND source_uri=? AND byte_size=? AND modified_at=?",
            arrayOf(ALGORITHM_VERSION, draft.uri.toString(), byteSize.toString(), modifiedAt.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val labels = cursor.getString(2).takeIf(String::isNotEmpty)?.split('\u001f') ?: emptyList()
            return draft.copy(score = cursor.getInt(0), kind = PhotoKind.valueOf(cursor.getString(1)), semanticLabels = labels)
        }
    }

    fun put(candidate: PhotoCandidate, byteSize: Long, modifiedAt: Long) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO analysis_cache VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf(ALGORITHM_VERSION, candidate.uri.toString(), byteSize, modifiedAt, candidate.score, candidate.kind.name, candidate.semanticLabels.joinToString("\u001f"))
        )
    }
}
