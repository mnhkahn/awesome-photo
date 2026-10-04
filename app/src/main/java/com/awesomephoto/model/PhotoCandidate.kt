package com.awesomephoto.model

import android.net.Uri

enum class PhotoKind(val label: String) { ALL("全部"), PERSON("人物"), SCENERY("风景"), ARCHITECTURE("建筑"), ART("人文艺术"), OTHER("其他") }
enum class WallpaperTarget(val label: String) { DESKTOP("桌面"), APP("App") }
enum class ScoreBand(val label: String) { ALL("全部"), ABOVE_80("80+"), FROM_70("70–79"), FROM_60("60–69"), BELOW_60("60 以下") }
enum class Orientation(val label: String) { ALL("全部"), LANDSCAPE("横屏"), PORTRAIT("竖屏") }

data class PhotoCandidate(
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val dateMs: Long,
    val target: WallpaperTarget,
    val kind: PhotoKind,
    val score: Int,
    val semanticLabels: List<String>,
    val scoreDetails: List<ScoreDetail> = emptyList()
) {
    val scoreBand: ScoreBand get() = when { score >= 80 -> ScoreBand.ABOVE_80; score >= 70 -> ScoreBand.FROM_70; score >= 60 -> ScoreBand.FROM_60; else -> ScoreBand.BELOW_60 }
    val orientation: Orientation get() = if (height > width) Orientation.PORTRAIT else Orientation.LANDSCAPE
}

data class ScanSettings(
    val aestheticModelId: String = "nima-mobile",
    val scanLimit: Int = 100,
    val desktopMinWidth: Int = 1920,
    val desktopMinHeight: Int = 1080,
    val appMinWidth: Int = 1080,
    val appMinHeight: Int = 1920,
    val dateStartMs: Long? = null,
    val dateEndMs: Long? = null,
)

enum class ScanStage { PREPARING, DISCOVERING, CHECKING_SIZE, ANALYSING }

data class ScanProgress(
    val done: Int = 0,
    val total: Int = 0,
    val currentName: String = "",
    val stage: ScanStage = ScanStage.PREPARING,
) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/** The actual normalized component and its measurement at analysis time. */
data class ScoreDetail(val name: String, val weight: Double, val value: Float, val policy: String, val reason: String) {
    val points: Double get() = weight * value
}
