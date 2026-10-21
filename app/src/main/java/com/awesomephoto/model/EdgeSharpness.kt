package com.awesomephoto.model

import kotlin.math.abs

/** Local edge steepness, independent of the proportion of flat pixels in the frame. */
object EdgeSharpness {
    // Initial wallpaper sufficiency threshold, not a probability or a perceptual guarantee.
    const val WALLPAPER_THRESHOLD = .70f

    data class FocusRegion(val x: Int, val y: Int, val size: Int, val edges: Int, val steepness: Float) {
        val value get() = (steepness / WALLPAPER_THRESHOLD).coerceIn(0f, 1f)
    }

    data class Measurement(val value: Float, val edges: Int, val steepness: Float? = null, val focus: FocusRegion? = null) {
        val reliable: Boolean get() = edges >= 12
    }

    fun measure(luma: FloatArray, width: Int, height: Int, mask: BooleanArray? = null, findFocus: Boolean = false): Measurement {
        require(width > 0 && height > 0 && luma.size == width * height)
        require(mask == null || mask.size == luma.size)
        if (width < 9 || height < 9) return Measurement(.5f, 0)
        // A small binomial filter reduces single-pixel noise before measuring edge profiles.
        val smooth = luma.copyOf()
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            smooth[i] = (luma[i] * 4 + (luma[i - 1] + luma[i + 1] + luma[i - width] + luma[i + width]) * 2 +
                luma[i - width - 1] + luma[i - width + 1] + luma[i + width - 1] + luma[i + width + 1]) / 16
        }
        val values = ArrayList<Float>()
        // Overlapping windows find sustained local detail without requiring a person label.
        // This is evidence of a focused area, not semantic identification of the subject.
        val windowSize = (minOf(width, height) / 6).coerceIn(48, 192).coerceAtMost(minOf(width, height))
        fun origins(length: Int): List<Int> = (0..length - windowSize step maxOf(1, windowSize / 2))
            .toList().plus(length - windowSize).distinct()
        val xs = if (findFocus) origins(width) else emptyList()
        val ys = if (findFocus) origins(height) else emptyList()
        val windows = Array(xs.size * ys.size) { ArrayList<Float>() }
        val xWindows = Array(width) { x -> xs.indices.filter { x >= xs[it] && x < xs[it] + windowSize } }
        val yWindows = Array(height) { y -> ys.indices.filter { y >= ys[it] && y < ys[it] + windowSize } }
        val directions = intArrayOf(1, width)
        for (y in 4 until height - 4) for (x in 4 until width - 4) {
            val i = y * width + x
            if (mask != null && !mask[i]) continue
            for (step in directions) {
                val gradient = abs(smooth[i + step] - smooth[i - step])
                if (gradient < .02f || gradient < abs(smooth[i] - smooth[i - 2 * step]) ||
                    gradient <= abs(smooth[i + 2 * step] - smooth[i])) continue
                var low = 1f
                var high = 0f
                for (d in -4..4) {
                    val v = smooth[i + d * step]
                    low = minOf(low, v); high = maxOf(high, v)
                }
                val contrast = high - low
                // Ignore flat/weak patches and oscillating texture instead of counting them as blur.
                if (contrast < .10f || abs(smooth[i + 4 * step] - smooth[i - 4 * step]) < contrast * .8f) continue
                // A sharp step after the filter spans 75% of its contrast across these two pixels.
                val value = (gradient / contrast / .75f).coerceIn(0f, 1f)
                values.add(value)
                for (wy in yWindows[y]) for (wx in xWindows[x]) windows[wy * xs.size + wx].add(value)
            }
        }
        if (values.size < 12) return Measurement(.5f, values.size)
        fun median(samples: ArrayList<Float>): Float {
            samples.sort()
            val middle = samples.size / 2
            return if (samples.size % 2 == 0) (samples[middle - 1] + samples[middle]) / 2 else samples[middle]
        }
        // Reject isolated sharp specks: a window needs 64 edges and at least 2% of all edges.
        val minimum = maxOf(64, (values.size + 49) / 50)
        val focus = windows.indices.filter { windows[it].size >= minimum }.map { index ->
            FocusRegion(xs[index % xs.size], ys[index / xs.size], windowSize, windows[index].size, median(windows[index]))
        }.maxByOrNull { it.steepness }
        val median = median(values)
        return Measurement((median / WALLPAPER_THRESHOLD).coerceIn(0f, 1f), values.size, median, focus)
    }
}
