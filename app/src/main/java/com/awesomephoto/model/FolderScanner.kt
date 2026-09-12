package com.awesomephoto.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.content.ContentUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FolderScanner(private val context: Context) {
    private val contentResolver = context.contentResolver
    private val cache = AnalysisCache(context)
    private val batchSize = 4

    data class ScanResult(
        val candidates: List<PhotoCandidate>,
        val dateMatchedCount: Int,
        val sizeEligibleCount: Int,
    )

    suspend fun scan(settings: ScanSettings, report: (ScanProgress) -> Unit): ScanResult = withContext(Dispatchers.Default) {
        report(ScanProgress(0, 0, "正在查找所选日期的图片", ScanStage.DISCOVERING))
        val files = collectImageFiles(settings, report)
        val prefiltered = mutableListOf<PendingPhoto>()
        report(ScanProgress(0, files.size, "正在检查图片尺寸", ScanStage.CHECKING_SIZE))
        files.forEachIndexed { index, file ->
            val dateMs = file.modifiedAt
            if (dateMs >= (settings.dateStartMs ?: Long.MIN_VALUE) && dateMs <= (settings.dateEndMs ?: Long.MAX_VALUE)) {
                bounds(file.uri)?.let { size ->
                    eligible(file.uri, file.name, size.first, size.second, dateMs, settings)?.let { candidate ->
                        prefiltered += PendingPhoto(candidate, file.byteSize, dateMs)
                    }
                }
            }
            val current = index + 1
            if (current % 25 == 0 || current == files.size) {
                report(ScanProgress(current, files.size, file.name, ScanStage.CHECKING_SIZE))
            }
        }
        val results = mutableListOf<PhotoCandidate>()
        var done = 0
        var analyzer: SegFormerAnalyzer? = null
        try {
            prefiltered.chunked(batchSize).forEach { chunk ->
                val misses = mutableListOf<PendingPhoto>()
                chunk.forEach { pending ->
                    cache.get(pending.candidate, pending.byteSize, pending.modifiedAt)?.let(results::add) ?: misses.add(pending)
                }
                val decoded = misses.mapNotNull { item -> decodeForAnalysis(item.candidate.uri)?.let { item to it } }
                if (decoded.isNotEmpty()) {
                    // Only create the 4 MB model session when uncached images actually need inference.
                    val semantic = (analyzer ?: SegFormerAnalyzer(context).also { analyzer = it }).analyzeBatch(decoded.map { it.second })
                    decoded.zip(semantic).forEach { (pair, stats) ->
                        val (pending, bitmap) = pair
                        val score = PhotoScorer.score(bitmap, stats)
                        val candidate = pending.candidate.copy(score = score, kind = stats.category, semanticLabels = stats.labelNames)
                        cache.put(candidate, pending.byteSize, pending.modifiedAt)
                        results += candidate
                    }
                }
                done += chunk.size
                report(ScanProgress(done, prefiltered.size, chunk.last().candidate.displayName, ScanStage.ANALYSING))
            }
        } finally {
            analyzer?.close()
        }
        ScanResult(
            candidates = results.sortedByDescending { it.score },
            dateMatchedCount = files.size,
            sizeEligibleCount = prefiltered.size,
        )
    }

    private data class PendingPhoto(val candidate: PhotoCandidate, val byteSize: Long, val modifiedAt: Long)

    private data class MediaPhoto(val uri: Uri, val name: String, val byteSize: Long, val modifiedAt: Long)

    private fun collectImageFiles(settings: ScanSettings, report: (ScanProgress) -> Unit): List<MediaPhoto> {
        val start = (settings.dateStartMs ?: Long.MIN_VALUE) / 1000
        val end = (settings.dateEndMs ?: Long.MAX_VALUE) / 1000
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_MODIFIED)
        val result = mutableListOf<MediaPhoto>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Images.Media.DATE_MODIFIED} BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString()), "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext() && (settings.scanLimit <= 0 || result.size < settings.scanLimit)) {
                result += MediaPhoto(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id)), cursor.getString(name) ?: "未命名图片", cursor.getLong(size), cursor.getLong(modified) * 1000)
            }
        }
        report(ScanProgress(result.size, result.size, "", ScanStage.DISCOVERING))
        return result
    }

    private fun bounds(uri: Uri): Pair<Int, Int>? = contentResolver.openInputStream(uri)?.use { input ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, options)
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    private fun eligible(uri: Uri, name: String, width: Int, height: Int, dateMs: Long, s: ScanSettings): PhotoCandidate? = when {
        width >= height && width >= s.desktopMinWidth && height >= s.desktopMinHeight -> PhotoCandidate(uri, name, width, height, dateMs, WallpaperTarget.DESKTOP, PhotoKind.OTHER, 0, emptyList())
        height > width && width >= s.appMinWidth && height >= s.appMinHeight -> PhotoCandidate(uri, name, width, height, dateMs, WallpaperTarget.APP, PhotoKind.OTHER, 0, emptyList())
        else -> null
    }

    private fun decodeForAnalysis(uri: Uri): Bitmap? = contentResolver.openInputStream(uri)?.use { input ->
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeStream(input, null, options)
    }
}
