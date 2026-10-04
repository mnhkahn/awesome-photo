package com.awesomephoto

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class UpdateStage { IDLE, CHECKING, DOWNLOADING, READY, FAILED }
data class UpdateState(val stage: UpdateStage = UpdateStage.IDLE, val version: String = "", val notes: String = "", val progress: Float = 0f, val message: String = "", val id: Long = -1)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var running = false
    private val apk: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "app-update.apk")

    init { checkForUpdate() }

    fun checkForUpdate() {
        if (running) return
        running = true
        viewModelScope.launch {
            try {
                var id = prefs.getLong("id", -1)
                if (id >= 0 && prefs.getLong("versionCode", 0) <= BuildConfig.VERSION_CODE) { clearDownload(); id = -1 }
                if (id < 0 || mutable.value.stage == UpdateStage.FAILED) {
                    clearDownload()
                    mutable.value = UpdateState(UpdateStage.CHECKING)
                    val update = AppUpdateClient.check()
                    if (update == null) { mutable.value = UpdateState(); return@launch }
                    val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
                        .setTitle("壁纸照片分析器 ${update.versionName}")
                        .setDescription("正在下载更新，下载完成后可安装")
                        .setMimeType("application/vnd.android.package-archive")
                        .setAllowedOverMetered(false)
                        .setAllowedOverRoaming(false)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "app-update.apk")
                    id = downloads.enqueue(request)
                    prefs.edit().putLong("id", id).putLong("versionCode", update.versionCode)
                        .putString("versionName", update.versionName).putString("notes", update.notes)
                        .putLong("size", update.sizeBytes).apply()
                }
                val current = UpdateState(UpdateStage.DOWNLOADING, prefs.getString("versionName", "")!!, prefs.getString("notes", "")!!, id = id)
                while (true) {
                    val snapshot = withContext(Dispatchers.IO) {
                        downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
                            check(c.moveToFirst()) { "下载任务已移除，请重试" }
                            Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                        }
                    }
                    when (snapshot.first) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            withContext(Dispatchers.IO) { verifyApk() }
                            mutable.value = current.copy(stage = UpdateStage.READY, progress = 1f, message = "下载完成，可以安装")
                            break
                        }
                        DownloadManager.STATUS_FAILED -> error("下载失败或链接已过期，请重试获取新地址")
                        else -> mutable.value = current.copy(progress = (snapshot.second.toFloat() / prefs.getLong("size", 1)).coerceIn(0f, 1f),
                            message = if (snapshot.first == DownloadManager.STATUS_PAUSED) "等待 Wi-Fi 或网络恢复" else "正在后台下载（仅使用非计费网络）")
                    }
                    delay(1000)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(stage = UpdateStage.FAILED, message = "更新检查、下载或安装包校验失败，请重试")
            } finally { running = false }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk() {
        check(apk.isFile && apk.length() == prefs.getLong("size", -1)) { "安装包不完整" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val downloaded = checkNotNull(pm.getPackageArchiveInfo(apk.absolutePath, flags)) { "安装包无法读取" }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val code = if (Build.VERSION.SDK_INT >= 28) downloaded.longVersionCode else downloaded.versionCode.toLong()
        check(downloaded.packageName == context.packageName && code == prefs.getLong("versionCode", -1) && code > BuildConfig.VERSION_CODE) { "安装包版本不匹配" }
        val incoming = if (Build.VERSION.SDK_INT >= 28) downloaded.signingInfo?.apkContentsSigners else downloaded.signatures
        val existing = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(!incoming.isNullOrEmpty() && !existing.isNullOrEmpty() && incoming.toSet() == existing.toSet()) { "安装包签名不匹配" }
    }

    fun installIntent(): Intent {
        check(mutable.value.stage == UpdateStage.READY)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun clearDownload() {
        val id = prefs.getLong("id", -1)
        if (id >= 0) downloads.remove(id)
        apk.delete()
        prefs.edit().clear().apply()
    }
}
