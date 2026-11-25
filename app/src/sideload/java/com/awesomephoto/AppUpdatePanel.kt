package com.awesomephoto

import androidx.compose.ui.res.stringResource
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
            .onFailure { installError = context.getString(R.string.update_install_failed) }
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
                TextButton(onClick = { hidden = false }) { Text(if (state.stage == UpdateStage.READY) stringResource(R.string.update_downloaded) else stringResource(R.string.update_status)) }
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = { hidden = true },
        title = { Text(if (state.version.isBlank()) stringResource(R.string.update_title) else stringResource(R.string.update_version, state.version)) },
        text = { Column {
            Text(state.notes)
            Text(installError ?: state.message)
            if (state.stage == UpdateStage.DOWNLOADING) LinearProgressIndicator(progress = { state.progress })
            if (state.stage == UpdateStage.READY) Text(stringResource(R.string.update_install_notice))
        } },
        confirmButton = {
            when (state.stage) {
                UpdateStage.READY -> TextButton(onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) install()
                    else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                }) { Text(stringResource(R.string.update_install)) }
                UpdateStage.FAILED -> TextButton(onClick = { installError = null; updater.checkForUpdate() }) { Text(stringResource(R.string.retry)) }
                else -> TextButton(onClick = { hidden = true }) { Text(stringResource(R.string.update_background)) }
            }
        },
        dismissButton = { TextButton(onClick = { hidden = true }) { Text(stringResource(R.string.later)) } }
    )
}
