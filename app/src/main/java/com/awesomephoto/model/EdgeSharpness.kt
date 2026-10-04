package com.awesomephoto.model

import kotlin.math.abs

/** Local edge steepness, independent of the proportion of flat pixels in the frame. */
object EdgeSharpness {
    // Initial wallpaper sufficiency threshold, not a probability or a perceptual guarantee.
    const val WALLPAPER_THRESHOLD = .70f

    data class Measurement(val value: Float, val edges: Int, val steepness: Float? = null) {
        val reliable: Boolean get() = edges >= 12
    }

    fun measure(luma: FloatArray, width: Int, height: Int, mask: BooleanArray? = null): Measurement {
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
                values.add((gradient / contrast / .75f).coerceIn(0f, 1f))
            }
        }
        if (values.size < 12) return Measurement(.5f, values.size)
        values.sort()
        val middle = values.size / 2
        val median = if (values.size % 2 == 0) (values[middle - 1] + values[middle]) / 2 else values[middle]
        return Measurement((median / WALLPAPER_THRESHOLD).coerceIn(0f, 1f), values.size, median)
    }
}
