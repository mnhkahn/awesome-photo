package com.awesomephoto

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppUpdatePanel(updater: AppUpdateViewModel = viewModel()) {
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var hidden by rememberSaveable { mutableStateOf(false) }
    var prompted by rememberSaveable { mutableStateOf(-1L) }
    var installError by remember { mutableStateOf<String?>(null) }
    fun install() {
        runCatching { context.startActivity(updater.installIntent()) }
            .onFailure { installError = "无法打开系统安装器，请稍后重试" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls() && state.stage == UpdateStage.READY) install()
    }
    LaunchedEffect(state.stage, state.id, lifecycle) {
        if (state.stage == UpdateStage.READY && lifecycle == Lifecycle.State.RESUMED && prompted != state.id) {
            hidden = false
            prompted = state.id
            if (context.packageManager.canRequestPackageInstalls()) install()
        }
    }
    if (state.stage == UpdateStage.IDLE || state.stage == UpdateStage.CHECKING) return
    if (hidden) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.BottomEnd) {
            Surface(tonalElevation = 6.dp, shadowElevation = 4.dp) {
                TextButton(onClick = { hidden = false }) { Text(if (state.stage == UpdateStage.READY) "更新已下载，点击安装" else "查看更新状态") }
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = { hidden = true },
        title = { Text(if (state.version.isBlank()) "应用更新" else "更新至 ${state.version}") },
        text = { Column {
            Text(state.notes)
            Text(installError ?: state.message)
            if (state.stage == UpdateStage.DOWNLOADING) LinearProgressIndicator(progress = { state.progress })
            if (state.stage == UpdateStage.READY) Text("安装由系统确认。首次使用需允许本应用安装更新。")
        } },
        confirmButton = {
            when (state.stage) {
                UpdateStage.READY -> TextButton(onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) install()
                    else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                }) { Text("安装更新") }
                UpdateStage.FAILED -> TextButton(onClick = { installError = null; updater.checkForUpdate() }) { Text("重试") }
                else -> TextButton(onClick = { hidden = true }) { Text("后台下载") }
            }
        },
        dismissButton = { TextButton(onClick = { hidden = true }) { Text("稍后") } }
    )
}
