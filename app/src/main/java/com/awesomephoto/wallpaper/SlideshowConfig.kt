package com.awesomephoto.wallpaper

import kotlin.random.Random

data class SlideshowConfig(
    val intervalMinutes: Int = 30,
    val shuffle: Boolean = true,
    val fillScreen: Boolean = true,
) {
    init { require(intervalMinutes in INTERVALS) }
    companion object { val INTERVALS = listOf(1, 5, 15, 30, 60, 180, 360, 1440) }
}

/** Shuffle excludes the currently visible image, including at cycle boundaries. */
internal fun nextPhotoIndex(current: Int, count: Int, shuffle: Boolean, random: Random = Random.Default): Int {
    require(count > 0)
    if (count == 1) return 0
    if (!shuffle) return (current + 1).mod(count)
    if (current !in 0 until count) return random.nextInt(count)
    val next = random.nextInt(count - 1)
    return if (next >= current) next + 1 else next
}
