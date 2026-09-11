package com.awesomephoto.model

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Same score policy as the Python prototype: technical quality + semantic composition. */
object PhotoScorer {
    data class SemanticStats(val labels: IntArray, val width: Int, val height: Int) {
        private fun coverage(ids: Set<Int>) = labels.count { it in ids }.toFloat() / labels.size
        private fun centroidX(ids: Set<Int>): Float? {
            var sum = 0f; var count = 0
            labels.forEachIndexed { index, id -> if (id in ids) { sum += index % width; count++ } }
            return if (count == 0) null else sum / count / width
        }
        val hasPerson get() = coverage(setOf(12)) > .015f
        val hasScenery get() = coverage(setOf(2, 16, 21, 26, 60, 113, 128)) > .12f
        val labelNames: List<String> get() = buildList {
            if (coverage(setOf(2)) > .03f) add("天空")
            if (coverage(setOf(16)) > .03f) add("山")
            if (coverage(setOf(21, 26, 60, 113, 128)) > .03f) add("水景")
            if (hasPerson) add("人物")
            if (coverage(setOf(1, 48, 84)) > .03f) add("建筑")
        }
        val category: PhotoKind get() = when { hasPerson -> PhotoKind.PERSON; hasScenery -> PhotoKind.SCENERY; else -> PhotoKind.OTHER }
        fun compositionScore(): Float {
            val subject = centroidX(setOf(12, 1, 16, 84, 48)) ?: return 0.72f
            val distance = min(abs(subject - 1f / 3), abs(subject - 2f / 3))
            return (1f - distance / (1f / 3)).coerceIn(0.4f, 1f)
        }
    }

    fun score(bitmap: Bitmap, semantics: SemanticStats): Int {
        val sample = bitmap.scaledToFit(256)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        val luminance = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xff) * .299f + (p shr 8 and 0xff) * .587f + (p and 0xff) * .114f) / 255f
        }
        val mean = luminance.average().toFloat()
        val exposure = (1f - abs(mean - .5f) * 2f).coerceIn(0f, 1f)
        val contrast = sqrt(luminance.map { (it - mean) * (it - mean) }.average()).toFloat().coerceAtMost(.25f) / .25f
        var laplacianEnergy = 0f; var samples = 0
        for (y in 1 until sample.height - 1) for (x in 1 until sample.width - 1) {
            val i = y * sample.width + x
            val lap = 4 * luminance[i] - luminance[i - 1] - luminance[i + 1] - luminance[i - sample.width] - luminance[i + sample.width]
            laplacianEnergy += lap * lap; samples++
        }
        val sharpness = (sqrt(laplacianEnergy / max(samples, 1)) / .35f).coerceIn(0f, 1f)
        val material = if (semantics.hasScenery) 1f else if (semantics.hasPerson) .78f else .58f
        return (100f * (.30f * sharpness + .20f * exposure + .15f * contrast + .20f * semantics.compositionScore() + .15f * material)).toInt().coerceIn(0, 100)
    }

    private fun Bitmap.scaledToFit(edge: Int): Bitmap {
        val scale = min(1f, edge.toFloat() / max(width, height))
        return if (scale == 1f) this else Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }
}
