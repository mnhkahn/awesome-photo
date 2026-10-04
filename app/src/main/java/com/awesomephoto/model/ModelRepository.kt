package com.awesomephoto.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ModelRepository(context: Context) {
    val catalog = ModelCatalog.load(context)
    private val preferences = context.getSharedPreferences("analysis_models", Context.MODE_PRIVATE)
    // Keep weights across app restarts, updates and cache clearing; exclude large files from backup.
    private val store = ModelDownloadStore(File(context.noBackupFilesDir, "analysis-models"))
    val selected get() = catalog.aesthetic(preferences.getString("aesthetic", null) ?: catalog.aesthetics.first().id)
    fun select(id: String) {
        require(catalog.aesthetics.any { it.id == id })
        preferences.edit().putString("aesthetic", id).apply()
    }
    fun downloadedIds() = catalog.models.filter { store.has(it) }.map { it.id }.toSet()
    fun ready(id: String) = store.has(catalog.aesthetic(id)) && store.has(catalog.segmentation)

    suspend fun prepare(model: DownloadableModel, progress: (String, Long, Long) -> Unit = { _, _, _ -> }): File =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val coroutine = currentCoroutineContext()
                progress(model.name, 0, model.bytes)
                store.prepare(model, { coroutine.ensureActive() }) { done, total -> progress(model.name, done, total) }
            }
        }
    companion object { private val mutex = Mutex() }
}
