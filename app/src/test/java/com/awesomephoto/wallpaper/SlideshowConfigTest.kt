package com.awesomephoto.wallpaper

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SlideshowConfigTest {
    @Test fun sequentialPlaybackWrapsAndStartsAtFirstPhoto() {
        assertEquals(0, nextPhotoIndex(-1, 3, false))
        assertEquals(2, nextPhotoIndex(1, 3, false))
        assertEquals(0, nextPhotoIndex(2, 3, false))
    }

    @Test fun shuffleNeverImmediatelyRepeatsAndCanReachEveryOtherPhoto() {
        val random = Random(42)
        for (current in 0 until 5) {
            val choices = List(1000) { nextPhotoIndex(current, 5, true, random) }.toSet()
            assertEquals((0 until 5).toSet() - current, choices)
        }
    }

    @Test fun singlePhotoStaysFixedInBothModes() {
        assertEquals(0, nextPhotoIndex(0, 1, true))
        assertEquals(0, nextPhotoIndex(0, 1, false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyAlbumCannotBePlayed() { nextPhotoIndex(-1, 0, true) }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedIntervalIsRejected() { SlideshowConfig(intervalMinutes = 0) }
}
