package com.awesomephoto.model

import androidx.annotation.StringRes
import com.awesomephoto.R
import android.net.Uri

enum class PhotoKind(val label: String, @StringRes val labelRes: Int) { ALL("全部", R.string.filter_all), PERSON("人物", R.string.filter_people), SCENERY("风景", R.string.filter_scenery), ARCHITECTURE("建筑", R.string.filter_architecture), ART("人文艺术", R.string.filter_art), OTHER("其他", R.string.filter_other) }
enum class WallpaperTarget(@StringRes val labelRes: Int) { DESKTOP(R.string.target_desktop), APP(R.string.target_phone) }
enum class ScoreBand(@StringRes val labelRes: Int) { ALL(R.string.filter_all), ABOVE_80(R.string.score_80_plus), FROM_70(R.string.score_70), FROM_60(R.string.score_60), BELOW_60(R.string.score_below_60) }
enum class Orientation(@StringRes val labelRes: Int) { ALL(R.string.filter_all), LANDSCAPE(R.string.orientation_landscape), PORTRAIT(R.string.orientation_portrait) }

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

data class ScoreCopy(val name: String, val policy: String, val reason: String)

/** Cache both explanations with the measurement so switching languages never changes the score. */
data class ScoreDetail(
    val name: String,
    val weight: Double,
    val value: Float,
    val policy: String,
    val reason: String,
    val english: ScoreCopy? = null,
) {
    val points: Double get() = weight * value

    fun displayText(chinese: Boolean): ScoreCopy =
        if (chinese) ScoreCopy(name, policy, reason) else english ?: ScoreCopy(name, policy, reason)
}
