package com.awesomephoto

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.awesomephoto.model.FolderScanner
import com.awesomephoto.model.PhotoCandidate
import com.awesomephoto.model.PhotoKind
import com.awesomephoto.model.ScanProgress
import com.awesomephoto.model.ScanSettings
import com.awesomephoto.model.SegFormerAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScanUiState(
    val folder: Uri? = null,
    val settings: ScanSettings = ScanSettings(),
    val isScanning: Boolean = false,
    val progress: ScanProgress = ScanProgress(),
    val candidates: List<PhotoCandidate> = emptyList(),
    val error: String? = null
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    fun setFolder(uri: Uri) { _state.value = _state.value.copy(folder = uri, error = null) }
    fun updateSettings(settings: ScanSettings) { _state.value = _state.value.copy(settings = settings) }

    fun scan() {
        val snapshot = _state.value
        val folder = snapshot.folder ?: run { _state.value = snapshot.copy(error = "请先选择照片目录"); return }
        viewModelScope.launch {
            _state.value = snapshot.copy(isScanning = true, candidates = emptyList(), error = null, progress = ScanProgress())
            try {
                val candidates = withContext(Dispatchers.IO) {
                    SegFormerAnalyzer(getApplication()).use { analyzer ->
                        FolderScanner(getApplication(), analyzer).scan(folder, snapshot.settings) { progress ->
                            _state.value = _state.value.copy(progress = progress)
                        }
                    }
                }
                _state.value = _state.value.copy(isScanning = false, candidates = candidates)
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
