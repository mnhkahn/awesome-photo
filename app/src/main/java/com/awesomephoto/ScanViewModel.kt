package com.awesomephoto

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.awesomephoto.model.ModelRepository
import com.awesomephoto.model.DownloadableModel
import kotlinx.coroutines.CancellationException
import com.awesomephoto.model.FolderScanner
import com.awesomephoto.model.PhotoCandidate
import com.awesomephoto.model.PhotoDateIndex
import com.awesomephoto.model.ScanProgress
import com.awesomephoto.model.ScanSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class ScanUiState(
    val models: List<DownloadableModel> = emptyList(),
    val downloadedModels: Set<String> = emptySet(),
    val preparingModels: Boolean = false,
    val modelsReady: Boolean = false,
    val modelProgress: ModelProgress? = null,
    val hasPhotoAccess: Boolean = false,
    val settings: ScanSettings = ScanSettings(),
    val isScanning: Boolean = false,
    val progress: ScanProgress = ScanProgress(),
    val candidates: List<PhotoCandidate> = emptyList(),
    val lastScan: ScanSummary? = null,
    val dailyPhotoCounts: List<PhotoDateIndex.DayCount> = emptyList(),
    val isDateIndexing: Boolean = false,
    val error: ScanMessage? = null
)

data class ModelProgress(val model: DownloadableModel, val percent: Long)

class ScanMessage(@StringRes val resId: Int, vararg val arguments: Any)

data class ScanSummary(
    val dateMatchedCount: Int,
    val sizeEligibleCount: Int,
    val scoredCount: Int,
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val models = ModelRepository(app)
    private val _state = MutableStateFlow(ScanUiState(
        settings = ScanSettings(aestheticModelId = models.selected.id),
        models = models.catalog.models, downloadedModels = models.downloadedIds(),
        modelsReady = models.ready(models.selected.id)
    ))
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private val requestedMonths = mutableSetOf<String>()
    private val pendingMonthLoads = mutableSetOf<String>()

    init {
        if (!_state.value.modelsReady) downloadModels()
    }

    fun selectModel(id: String) {
        if (_state.value.isScanning || _state.value.preparingModels) return
        models.select(id)
        _state.value = _state.value.copy(settings = _state.value.settings.copy(aestheticModelId = id),
            modelsReady = models.ready(id), candidates = emptyList(), lastScan = null, error = null)
        downloadModels()
    }

    private fun downloadModels(onReady: () -> Unit = {}) {
        if (_state.value.isScanning || _state.value.preparingModels) return
        val chosen = models.catalog.aesthetic(_state.value.settings.aestheticModelId)
        _state.value = _state.value.copy(preparingModels = true, error = null, modelProgress = null)
        viewModelScope.launch {
            var prepared = false
            try {
                var last = 0L
                for (model in listOf(chosen, models.catalog.segmentation)) {
                    models.prepare(model) { _, done, total ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (done == 0L || done == total || now - last >= 200) {
                            last = now
                            _state.value = _state.value.copy(modelProgress = ModelProgress(model, done * 100 / total))
                        }
                    }
                }
                _state.value = _state.value.copy(modelsReady = true, modelProgress = null)
                prepared = true
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(modelsReady = false, modelProgress = null,
                    error = ScanMessage(R.string.models_failed, error))
            } finally {
                _state.value = _state.value.copy(preparingModels = false, downloadedModels = models.downloadedIds())
            }
            if (prepared) onReady()
        }
    }

    fun setPhotoAccess(granted: Boolean) {
        requestedMonths.clear()
        pendingMonthLoads.clear()
        _state.value = _state.value.copy(hasPhotoAccess = granted, error = null, dailyPhotoCounts = emptyList(), isDateIndexing = false)
    }
    fun updateSettings(settings: ScanSettings) { _state.value = _state.value.copy(settings = settings) }

    /** The scan cap follows the confirmed date range instead of a fixed debugging default. */
    fun setDateRange(startMs: Long, endMs: Long) {
        if (!_state.value.hasPhotoAccess) return
        viewModelScope.launch {
            val count = PhotoDateIndex.countByDay(getApplication(), startMs, endMs).sumOf { it.count }
            _state.value = _state.value.copy(settings = _state.value.settings.copy(scanLimit = count))
        }
    }

    /** Called only when the calendar exposes a month to the user. */
    fun loadCalendarMonth(year: Int, month: Int) {
        if (!_state.value.hasPhotoAccess) return
        val key = "$year-$month"
        if (!requestedMonths.add(key)) return
        pendingMonthLoads += key
        _state.value = _state.value.copy(isDateIndexing = true)
        val start = Calendar.getInstance().apply { clear(); set(year, month, 1, 0, 0, 0) }.timeInMillis
        val end = Calendar.getInstance().apply {
            clear(); set(year, month, 1, 23, 59, 59); set(Calendar.MILLISECOND, 999)
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        viewModelScope.launch {
            val counts = PhotoDateIndex.countByDay(getApplication(), start, end)
            pendingMonthLoads -= key
            if (_state.value.hasPhotoAccess) {
                val merged = (_state.value.dailyPhotoCounts + counts).distinctBy { it.dayStartMs }.sortedBy { it.dayStartMs }
                _state.value = _state.value.copy(dailyPhotoCounts = merged, isDateIndexing = pendingMonthLoads.isNotEmpty())
            }
        }
    }

    fun scan(dateStartMs: Long, dateEndMs: Long) {
        if (_state.value.isScanning || _state.value.preparingModels) return
        if (!_state.value.modelsReady) { downloadModels { scan(dateStartMs, dateEndMs) }; return }
        val snapshot = _state.value.copy(settings = _state.value.settings.copy(dateStartMs = dateStartMs, dateEndMs = dateEndMs))
        if (!snapshot.hasPhotoAccess) { _state.value = snapshot.copy(error = ScanMessage(R.string.scan_permission_failed)); return }
        viewModelScope.launch {
            _state.value = snapshot.copy(isScanning = true, candidates = emptyList(), lastScan = null, error = null, progress = ScanProgress())
            try {
                val result = withContext(Dispatchers.IO) {
                    FolderScanner(getApplication()).scan(snapshot.settings) { progress ->
                        _state.value = _state.value.copy(progress = progress)
                    }
                }
                _state.value = _state.value.copy(
                    isScanning = false,
                    candidates = result.candidates,
                    lastScan = ScanSummary(result.dateMatchedCount, result.sizeEligibleCount, result.candidates.size),
                )
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(isScanning = false, error = ScanMessage(R.string.scan_failed, error))
            }
        }
    }


    fun export(destinationTree: Uri, candidates: List<PhotoCandidate>) {
        if (candidates.isEmpty()) { _state.value = _state.value.copy(error = ScanMessage(R.string.export_empty)); return }
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val directory = DocumentFile.fromTreeUri(getApplication(), destinationTree) ?: throw LocalizedFailure(R.string.export_directory_failed)
                    candidates.count { candidate ->
                        val output = uniqueFile(directory, candidate.displayName) ?: return@count false
                        val resolver = getApplication<Application>().contentResolver
                        val input = resolver.openInputStream(candidate.uri) ?: return@count false
                        val target = resolver.openOutputStream(output.uri) ?: return@count false
                        input.use { source -> target.use { destination -> source.copyTo(destination) } }
                        true
                    }
                }
                _state.value = _state.value.copy(error = ScanMessage(R.string.export_success, count))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { _state.value = _state.value.copy(error = ScanMessage(R.string.export_failed, error)) }
        }
    }

    private fun uniqueFile(directory: DocumentFile, name: String): DocumentFile? {
        val stem = name.substringBeforeLast('.', name); val extension = name.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = if (index == 1) name else "$stem-$index${if (extension.isBlank()) "" else ".$extension"}"
            if (directory.findFile(candidate) == null) return directory.createFile("image/*", candidate)
            index++
        }
    }
}
