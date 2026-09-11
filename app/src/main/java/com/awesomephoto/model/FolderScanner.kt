package com.awesomephoto.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FolderScanner(private val context: Context, private val analyzer: SegFormerAnalyzer) {
    private val contentResolver = context.contentResolver
    private val cache = AnalysisCache(context)
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff", "heic", "heif")
    private val batchSize = 4

    suspend fun scan(folderUri: Uri, settings: ScanSettings, report: (ScanProgress) -> Unit): List<PhotoCandidate> = withContext(Dispatchers.Default) {
        val files = collectImageFiles(DocumentFile.fromTreeUri(context, folderUri), settings.scanLimit)
        val prefiltered = files.mapNotNull { file ->
            bounds(file.uri)?.let { size ->
                eligible(file.uri, file.name ?: "未命名图片", size.first, size.second, file.lastModified(), settings)?.let { candidate ->
                    PendingPhoto(candidate, file.length(), file.lastModified())
                }
            }
        }
        val results = mutableListOf<PhotoCandidate>()
        var done = 0
        prefiltered.chunked(batchSize).forEach { chunk ->
            val misses = mutableListOf<PendingPhoto>()
            chunk.forEach { pending ->
                cache.get(pending.candidate, pending.byteSize, pending.modifiedAt)?.let(results::add) ?: misses.add(pending)
            }
            val decoded = misses.mapNotNull { item -> decodeForAnalysis(item.candidate.uri)?.let { item to it } }
            if (decoded.isNotEmpty()) {
                val semantic = analyzer.analyzeBatch(decoded.map { it.second })
                decoded.zip(semantic).forEach { (pair, stats) ->
                    val (pending, bitmap) = pair
                    val score = PhotoScorer.score(bitmap, stats)
                    val candidate = pending.candidate.copy(score = score, kind = stats.category, semanticLabels = stats.labelNames)
                    cache.put(candidate, pending.byteSize, pending.modifiedAt)
                    results += candidate
                }
            }
            done += chunk.size
            report(ScanProgress(done, prefiltered.size, chunk.last().candidate.displayName))
        }
        results.sortedByDescending { it.score }
    }

    private data class PendingPhoto(val candidate: PhotoCandidate, val byteSize: Long, val modifiedAt: Long)

    private fun collectImageFiles(root: DocumentFile?, limit: Int): List<DocumentFile> {
        if (root == null) return emptyList()
        val result = mutableListOf<DocumentFile>()
        fun visit(directory: DocumentFile) {
            for (file in directory.listFiles()) {
                if (limit > 0 && result.size >= limit) return
                if (file.isDirectory) visit(file) else if (file.isFile && file.name?.substringAfterLast('.', "")?.lowercase() in imageExtensions) result += file
            }
        }
        visit(root)
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
