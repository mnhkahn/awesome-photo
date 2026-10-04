package com.awesomephoto.model

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import java.util.Locale

/** Android scoring policy: technical quality + semantic composition. */
object PhotoScorer {
    // Zero-based ADE20K IDs from the SegFormer model config. Classification only.
    private val sceneryIds = setOf(2, 4, 9, 16, 17, 21, 26, 29, 34, 46, 60, 66, 68, 72, 113, 128)
    private val architectureIds = setOf(1, 25, 48, 61, 79, 84)
    private val artIds = setOf(22, 132)

    data class SemanticStats(val labels: IntArray, val width: Int, val height: Int) {
        fun coverage(ids: Set<Int>) = labels.count { it in ids }.toFloat() / labels.size
        fun centroidX(ids: Set<Int>): Float? {
            var sum = 0f; var count = 0
            labels.forEachIndexed { index, id -> if (id in ids) { sum += index % width; count++ } }
            return if (count == 0) null else sum / count / width
        }
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
        fun compositionScore(): Float {
            val subject = centroidX(setOf(12, 1, 16, 84, 48)) ?: return 0.72f
            val distance = min(abs(subject - 1f / 3), abs(subject - 2f / 3))
            return (1f - distance / (1f / 3)).coerceIn(0.4f, 1f)
        }
    }

    data class Result(val details: List<ScoreDetail>) {
        val total: Int get() = details.sumOf { it.points }.toInt().coerceIn(0, 100)
    }

    private fun decimal(value: Float) = String.format(Locale.ROOT, "%.3f", value)
    private fun percent(value: Float) = String.format(Locale.ROOT, "%.1f%%", value * 100)

    fun score(bitmap: Bitmap, semantics: SemanticStats): Result {
        val sample = bitmap.scaledToFit(256)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        val luminance = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xff) * .299f + (p shr 8 and 0xff) * .587f + (p and 0xff) * .114f) / 255f
        }
        val mean = luminance.average().toFloat()
        val exposure = (1f - abs(mean - .5f) * 2f).coerceIn(0f, 1f)
        val deviation = sqrt(luminance.map { (it - mean) * (it - mean) }.average()).toFloat()
        val contrast = deviation.coerceAtMost(.25f) / .25f
        val sharpSample = bitmap.scaledToFit(1024)
        val sharpPixels = IntArray(sharpSample.width * sharpSample.height)
        sharpSample.getPixels(sharpPixels, 0, sharpSample.width, 0, 0, sharpSample.width, sharpSample.height)
        val sharpLuma = FloatArray(sharpPixels.size) { i ->
            val p = sharpPixels[i]
            ((p shr 16 and 0xff) * .299f + (p shr 8 and 0xff) * .587f + (p and 0xff) * .114f) / 255f
        }
        val edges = EdgeSharpness.measure(sharpLuma, sharpSample.width, sharpSample.height)
        val personEdges = if (semantics.hasPerson) {
            val mask = BooleanArray(sharpLuma.size) { i ->
                val x = (i % sharpSample.width) * semantics.width / sharpSample.width
                val y = (i / sharpSample.width) * semantics.height / sharpSample.height
                semantics.labels[y * semantics.width + x] == 12
            }
            EdgeSharpness.measure(sharpLuma, sharpSample.width, sharpSample.height, mask)
        } else null
        val sharpness = if (personEdges?.reliable == true) .7f * personEdges.value + .3f * edges.value else edges.value
        val sharpReason = if (!edges.reliable) {
            "仅找到 ${edges.edges} 个有效边缘，证据不足，暂按中性 50% 计分，不据此判断模糊。"
        } else {
            "在 ${sharpSample.width}×${sharpSample.height} 样本中检测到 ${edges.edges} 个有效边缘，全图边缘清晰度 ${percent(edges.value)}。" +
                if (personEdges?.reliable == true) "人物区域 ${personEdges.edges} 个边缘，清晰度 ${percent(personEdges.value)}；人物占 70%、全图占 30%。"
                else "人物区域未提供足够有效边缘，使用全图结果。"
        }
        if (sharpSample !== bitmap) sharpSample.recycle()
        val composition = semantics.compositionScore()
        val subject = semantics.centroidX(setOf(12, 1, 16, 84, 48))
        val details = listOf(
            ScoreDetail("清晰度", 30.0 / 85 * 100, sharpness,
                "最长边 1024 像素，轻度降噪后评估有效边缘的局部陡峭程度；平坦区域不计入平均，人物边缘充足时优先参考人物。",
                sharpReason),
            ScoreDetail("曝光", 20.0 / 85 * 100, exposure,
                "1 − |平均亮度 − 0.5| × 2；平均亮度越接近 0.5，得分越高。",
                "本图平均亮度 ${decimal(mean)}（0 为黑、1 为白），${if (mean < .5f) "低于" else "达到或高于"}目标 0.5。此项不单独检测局部过曝。"),
            ScoreDetail("对比度", 15.0 / 85 * 100, contrast,
                "亮度标准差 ÷ 0.25，上限 100%。",
                "本图亮度标准差 ${decimal(deviation)}，${if (deviation >= .25f) "已达到满分阈值" else "低于满分阈值 0.25"}。"),
            ScoreDetail("主体构图", 20.0 / 85 * 100, composition,
                "人物、建筑、山体的合并重心越接近横向 1/3 或 2/3，得分越高；最低 40%，无主体按 72%。",
                if (subject == null) "未识别到上述主体，使用默认 72%。" else "本图主体横向重心在 ${percent(subject)}，距最近三分线 ${percent(min(abs(subject - 1f / 3), abs(subject - 2f / 3)))} 画面宽度；不评估纵向位置。"),

        )
        if (sample !== bitmap) sample.recycle()
        return Result(details)
    }

    private fun Bitmap.scaledToFit(edge: Int): Bitmap {
        val scale = min(1f, edge.toFloat() / max(width, height))
        return if (scale == 1f) this else Bitmap.createScaledBitmap(this, max(1, (width * scale).toInt()), max(1, (height * scale).toInt()), true)
    }
}
