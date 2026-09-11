package com.awesomephoto.model

import android.net.Uri

enum class PhotoKind(val label: String) { ALL("全部"), PERSON("人物"), SCENERY("风景"), OTHER("其他") }
enum class WallpaperTarget(val label: String) { DESKTOP("桌面"), APP("App") }
enum class ScoreBand(val label: String) { ALL("全部"), ABOVE_95("95+"), FROM_90("90–94"), FROM_85("85–89"), BELOW_85("85 以下") }
enum class Orientation(val label: String) { ALL("全部"), LANDSCAPE("横屏"), PORTRAIT("竖屏") }

data class PhotoCandidate(
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val target: WallpaperTarget,
    val kind: PhotoKind,
    val score: Int,
    val semanticLabels: List<String>
) {
    val scoreBand: ScoreBand get() = when { score >= 95 -> ScoreBand.ABOVE_95; score >= 90 -> ScoreBand.FROM_90; score >= 85 -> ScoreBand.FROM_85; else -> ScoreBand.BELOW_85 }
    val orientation: Orientation get() = if (height > width) Orientation.PORTRAIT else Orientation.LANDSCAPE
}

data class ScanSettings(
    val scanLimit: Int = 100,
    val desktopMinWidth: Int = 1920,
    val desktopMinHeight: Int = 1080,
    val appMinWidth: Int = 1080,
    val appMinHeight: Int = 1920
)

data class ScanProgress(val done: Int = 0, val total: Int = 0, val currentName: String = "") {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}
