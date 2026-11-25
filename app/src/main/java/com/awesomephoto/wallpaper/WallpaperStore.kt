package com.awesomephoto.wallpaper

import com.awesomephoto.R
import android.content.Context
import android.graphics.BitmapFactory
import com.awesomephoto.model.PhotoCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Private durable copies avoid relying on revocable album permissions after setup. */
class WallpaperStore(private val context: Context) {
    val preferences = context.getSharedPreferences("wallpaper-slideshow", Context.MODE_PRIVATE)
    private val root = File(context.noBackupFilesDir, "wallpaper-slideshow")
    data class Album(val directory: File, val files: List<File>, val config: SlideshowConfig)

    fun read(preview: Boolean): Album? {
        val json = preferences.getString(if (preview) "pending" else "active", null) ?: return null
        return runCatching {
            val data = JSONObject(json)
            val directory = File(root, data.getString("directory"))
            val names = data.getJSONArray("files")
            Album(directory, List(names.length()) { File(directory, names.getString(it)) },
                SlideshowConfig(data.getInt("interval"), data.getBoolean("shuffle"), data.getBoolean("fill")))
        }.getOrNull()
    }

    fun prepare(photos: List<PhotoCandidate>, config: SlideshowConfig) {
        require(photos.isNotEmpty()) { context.getString(R.string.wallpaper_no_photos) }
        val directory = File(root, UUID.randomUUID().toString())
        check(directory.mkdirs()) { context.getString(R.string.wallpaper_mkdir_failed) }
        try {
            val names = photos.mapIndexed { index, photo ->
                val file = File(directory, "$index.image")
                val source = context.contentResolver.openInputStream(photo.uri) ?: error(context.getString(R.string.wallpaper_read_failed, photo.displayName))
                source.use { input -> file.outputStream().use { input.copyTo(it) } }
                // Check the actual copied data before replacing a working album; decode only a thumbnail.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { context.getString(R.string.wallpaper_decode_failed, photo.displayName) }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 128) inSampleSize *= 2
                }
                val thumbnail = BitmapFactory.decodeFile(file.path, options) ?: error(context.getString(R.string.wallpaper_decode_failed, photo.displayName))
                thumbnail.recycle()
                file.name
            }
            val json = JSONObject().put("directory", directory.name).put("files", JSONArray(names))
                .put("interval", config.intervalMinutes).put("shuffle", config.shuffle).put("fill", config.fillScreen)
            check(preferences.edit().putString("pending", json.toString()).commit()) { context.getString(R.string.wallpaper_pending_failed) }
            cleanup()
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun confirm() {
        val pending = preferences.getString("pending", null) ?: return
        check(preferences.edit().putString("active", pending).remove("pending").commit()) { context.getString(R.string.wallpaper_store_failed) }
        cleanup()
    }

    fun cancel() {
        preferences.edit().remove("pending").commit()
        cleanup()
    }

    private fun cleanup() {
        val keep = setOfNotNull(read(false)?.directory?.name, read(true)?.directory?.name)
        root.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }
}
