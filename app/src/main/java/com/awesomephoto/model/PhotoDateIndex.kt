package com.awesomephoto.model

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Lightweight preflight: file metadata only; does not decode pixels or load the model. */
object PhotoDateIndex {
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff", "heic", "heif")

    data class DayCount(val dayStartMs: Long, val count: Int)

    /** Counts one visible calendar range from metadata only; no pixels are decoded. */
    suspend fun countByDay(context: Context, startMs: Long, endMs: Long): List<DayCount> = withContext(Dispatchers.Default) {
        val counts = mutableMapOf<Long, Int>()
        val projection = arrayOf(MediaStore.Images.Media.DATE_MODIFIED)
        val selection = "${MediaStore.Images.Media.DATE_MODIFIED} BETWEEN ? AND ?"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
            arrayOf((startMs / 1000).toString(), (endMs / 1000).toString()), null
        )?.use { cursor ->
            val modified = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val day = dayStart(cursor.getLong(modified) * 1000)
                counts[day] = (counts[day] ?: 0) + 1
            }
        }
        counts.map { (day, count) -> DayCount(day, count) }.sortedBy { it.dayStartMs }
    }

    private fun dayStart(timeMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
