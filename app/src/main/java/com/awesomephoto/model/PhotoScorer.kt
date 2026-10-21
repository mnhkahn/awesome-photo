package com.awesomephoto.model

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

/** Local wallpaper assessment; subject category is a filter, not a bonus. */
object PhotoScorer {
    // Zero-based ADE20K IDs from the SegFormer model config. Classification only.
    private val sceneryIds = setOf(2, 4, 9, 16, 17, 21, 26, 29, 34, 46, 60, 66, 68, 72, 113, 128)
    private val architectureIds = setOf(1, 25, 48, 61, 79, 84)
    private val artIds = setOf(22, 132)

    data class SemanticStats(val labels: IntArray, val width: Int, val height: Int) {
        fun coverage(ids: Set<Int>) = labels.count { it in ids }.toFloat() / labels.size
        val hasPerson get() = coverage(setOf(12)) > .015f
        val hasScenery get() = coverage(sceneryIds) > .12f
        val hasArchitecture get() = coverage(architectureIds) > .12f
        val hasArt get() = coverage(artIds) > .08f
        val labelNames: List<String> get() = buildList {
            if (coverage(setOf(2)) > .03f) add("天空")
            if (coverage(setOf(16)) > .03f) add("山")
            if (coverage(setOf(21, 26, 60, 113, 128)) > .03f) add("水景")
            if (hasPerson) add("人物")
            if (coverage(architectureIds) > .03f) add("建筑")
            if (coverage(setOf(4, 9, 17, 29, 66, 72)) > .03f) add("草木")
            if (coverage(setOf(132)) > .03f) add("雕塑")
            if (coverage(setOf(22)) > .03f) add("绘画")
        }
        // Prefer a detected artwork over incidental visitors, and buildings over background sky.
        val category: PhotoKind get() = when {
            hasArt -> PhotoKind.ART
            hasPerson -> PhotoKind.PERSON
            hasArchitecture -> PhotoKind.ARCHITECTURE
            hasScenery -> PhotoKind.SCENERY
            else -> PhotoKind.OTHER
        }

    }

    data class Result(val details: List<ScoreDetail>) {
        val total: Int get() = (details.sumOf { it.points } + 0.00001).toInt().coerceIn(0, 100)
    }

    private fun percent(value: Float) = String.format(Locale.ROOT, "%.1f%%", value * 100)

    fun score(bitmap: Bitmap, semantics: SemanticStats, aesthetic: ScoreDetail): Result {
        val sharpSample = bitmap.scaledToFit(1024)
        val sharpPixels = IntArray(sharpSample.width * sharpSample.height)
        sharpSample.getPixels(sharpPixels, 0, sharpSample.width, 0, 0, sharpSample.width, sharpSample.height)
        val sharpLuma = FloatArray(sharpPixels.size) { i ->
            val p = sharpPixels[i]
            ((p shr 16 and 0xff) * .299f + (p shr 8 and 0xff) * .587f + (p and 0xff) * .114f) / 255f
        }
        val result = scoreSample(sharpLuma, sharpSample.width, sharpSample.height, semantics)
        if (sharpSample !== bitmap) sharpSample.recycle()
        return WallpaperAssessment.combine(aesthetic, WallpaperAssessment.subject(semantics), result.details.single())
    }

    internal fun scoreSample(sharpLuma: FloatArray, width: Int, height: Int, semantics: SemanticStats): Result {
        val edges = EdgeSharpness.measure(sharpLuma, width, height, findFocus = true)
        val personEdges = if (semantics.hasPerson) {
            val mask = BooleanArray(sharpLuma.size) { i ->
                val x = (i % width) * semantics.width / width
                val y = (i / width) * semantics.height / height
                semantics.labels[y * semantics.width + x] == 12
            }
            EdgeSharpness.measure(sharpLuma, width, height, mask)
        } else null
        val focus = edges.focus?.takeIf { it.value > edges.value }
        val sharpness = if (personEdges?.reliable == true) personEdges.value else focus?.value ?: edges.value
        val sharpReason = if (!edges.reliable) {
            "仅找到 ${edges.edges} 个有效边缘，证据不足，暂按中性 50% 计分，不据此判断模糊。"
        } else {
            "在 ${width}×${height} 样本中检测到 ${edges.edges} 个有效边缘，全图边缘指标 ${percent(edges.steepness ?: 0f)}。" +
                when {
                    personEdges?.reliable == true -> "人物区域 ${personEdges.edges} 个有效边缘，边缘指标 ${percent(personEdges.steepness ?: 0f)}，按人物区域计分，避免背景虚化拉低结果。"
                    focus != null -> "检测到局部清晰区域，有 ${focus.edges} 个有效边缘，边缘指标 ${percent(focus.steepness)}，按该区域计分；仅证明存在局部清晰细节，不能确认主体是否合焦，也不能据此确认大光圈。"
                    else -> "没有找到更清晰且边缘充足的局部区域，采用全图结果；无法区分背景虚化与主体失焦。"
                } + if (sharpness >= 1f) "所用区域达到清晰度参考线。" else "所用区域未达到清晰度参考线。"
        }
        val details = listOf(
            ScoreDetail("清晰度", 100.0, sharpness,
                "评价壁纸素材清晰度：最长边 1024 像素，边缘指标达到 ${percent(EdgeSharpness.WALLPAPER_THRESHOLD)} 即满分，低于参考线按比例计分。人物边缘充足时按人物区域；否则在全图与边缘充足的局部窗口中采用更清晰者。局部窗口边长为短边的 1/6（48–192 像素），半窗重叠，至少 64 个且占全图 2% 的有效边缘。降低浅景深背景的影响，但不能保证测到的就是主体；不评价美感。",
                sharpReason)
        )
        return Result(details)
    }

    private fun Bitmap.scaledToFit(edge: Int): Bitmap {
        val scale = min(1f, edge.toFloat() / max(width, height))
        return if (scale == 1f) this else Bitmap.createScaledBitmap(this, max(1, (width * scale).toInt()), max(1, (height * scale).toInt()), true)
    }
}
