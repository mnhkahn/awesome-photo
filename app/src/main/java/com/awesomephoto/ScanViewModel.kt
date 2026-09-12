package com.awesomephoto

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.awesomephoto.model.FolderScanner
import com.awesomephoto.model.PhotoCandidate
import com.awesomephoto.model.PhotoKind
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
    val hasPhotoAccess: Boolean = false,
    val settings: ScanSettings = ScanSettings(),
    val isScanning: Boolean = false,
    val progress: ScanProgress = ScanProgress(),
    val candidates: List<PhotoCandidate> = emptyList(),
    val lastScan: ScanSummary? = null,
    val dailyPhotoCounts: List<PhotoDateIndex.DayCount> = emptyList(),
    val isDateIndexing: Boolean = false,
    val error: String? = null
)

data class ScanSummary(
    val dateMatchedCount: Int,
    val sizeEligibleCount: Int,
    val scoredCount: Int,
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private val requestedMonths = mutableSetOf<String>()
    private val pendingMonthLoads = mutableSetOf<String>()

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
        val snapshot = _state.value.copy(settings = _state.value.settings.copy(dateStartMs = dateStartMs, dateEndMs = dateEndMs))
        if (!snapshot.hasPhotoAccess) { _state.value = snapshot.copy(error = "请允许访问系统相册后再分析"); return }
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
            } catch (error: Exception) {
                _state.value = _state.value.copy(isScanning = false, error = "分析失败：${error.message}")
            }
        }
    }

    fun count(kind: PhotoKind) = if (kind == PhotoKind.ALL) _state.value.candidates.size else _state.value.candidates.count { it.kind == kind }

    fun export(destinationTree: Uri, candidates: List<PhotoCandidate>) {
        if (candidates.isEmpty()) { _state.value = _state.value.copy(error = "当前筛选条件下没有图片可导出"); return }
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val directory = DocumentFile.fromTreeUri(getApplication(), destinationTree) ?: error("无法访问导出目录")
                    candidates.count { candidate ->
                        val output = uniqueFile(directory, candidate.displayName) ?: return@count false
                        val resolver = getApplication<Application>().contentResolver
                        val input = resolver.openInputStream(candidate.uri) ?: return@count false
                        val target = resolver.openOutputStream(output.uri) ?: return@count false
                        input.use { source -> target.use { destination -> source.copyTo(destination) } }
                        true
                    }
                }
                _state.value = _state.value.copy(error = "已导出 $count 张原图")
            } catch (error: Exception) { _state.value = _state.value.copy(error = "导出失败：${error.message}") }
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
