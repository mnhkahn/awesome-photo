package com.awesomephoto.model

import org.junit.Assert.*
import org.junit.Test

class EdgeSharpnessTest {
    private fun step(w: Int, h: Int) = FloatArray(w * h) { if (it % w < w / 2) .2f else .8f }
    private fun blur(a: FloatArray, w: Int, h: Int, radius: Int): FloatArray = FloatArray(a.size) { i ->
        var sum = 0f
        for (d in -radius..radius) sum += a[(i / w) * w + (i % w + d).coerceIn(0, w - 1)]
        sum / (2 * radius + 1)
    }
    @Test fun flatBackgroundDoesNotDiluteSharpEdges() {
        val narrow = EdgeSharpness.measure(step(64, 64), 64, 64)
        val wide = EdgeSharpness.measure(step(512, 64), 512, 64)
        assertTrue(narrow.reliable && wide.reliable)
        assertEquals(narrow.value, wide.value, .001f)
        assertTrue(wide.value > .95f)
    }
    @Test fun blurReducesSharpness() {
        val a = step(128, 128)
        val sharp = EdgeSharpness.measure(a, 128, 128)
        val blurred = EdgeSharpness.measure(blur(a, 128, 128, 2), 128, 128)
        val veryBlurred = EdgeSharpness.measure(blur(a, 128, 128, 4), 128, 128)
        assertTrue(blurred.reliable && veryBlurred.reliable)
        assertTrue(sharp.value > blurred.value && blurred.value > veryBlurred.value)
    }
    @Test fun adequateEdgesSaturateButBlurDoesNot() {
        val sharp = step(128, 128)
        val result = EdgeSharpness.measure(sharp, 128, 128)
        assertTrue(result.steepness!! >= EdgeSharpness.WALLPAPER_THRESHOLD)
        assertEquals(1f, result.value, 0f)
        val soft = EdgeSharpness.measure(blur(sharp, 128, 128, 4), 128, 128)
        assertTrue(soft.reliable && soft.steepness!! < EdgeSharpness.WALLPAPER_THRESHOLD)
        assertTrue(soft.value < 1f)
    }
    @Test fun flatImageAndWeakNoiseAreInconclusive() {
        val random = java.util.Random(1)
        for (a in listOf(FloatArray(128 * 128) { .6f }, FloatArray(128 * 128) { .6f + (random.nextFloat() - .5f) * .04f })) {
            val result = EdgeSharpness.measure(a, 128, 128)
            assertFalse(result.reliable)
            assertEquals(.5f, result.value, 0f)
        }
    }
    @Test fun strongerContrastDoesNotAutomaticallyMeanSharper() {
        val low = FloatArray(128 * 128) { if (it % 128 < 64) .4f else .6f }
        assertEquals(EdgeSharpness.measure(step(128, 128), 128, 128).value,
            EdgeSharpness.measure(low, 128, 128).value, .01f)
    }
    @Test fun subjectMaskCanExcludeSharpBackground() {
        val w = 128; val h = 128
        val sharp = step(w, h); val soft = blur(sharp, w, h, 4)
        val mixed = FloatArray(w * h) { if (it / w < 64) sharp[it] else soft[it] }
        val mask = BooleanArray(w * h) { it / w > 72 }
        assertTrue(EdgeSharpness.measure(mixed, w, h, mask).value < .6f)
    }
    @Test fun suppliedPhotoAndBlurredControlsWhenProvided() {
        val path = System.getenv("SHARPNESS_TEST_LUMA")
        org.junit.Assume.assumeTrue(!path.isNullOrBlank())
        val input = java.io.DataInputStream(java.io.File(path!!).inputStream().buffered())
        val w = input.readInt(); val h = input.readInt()
        val a = FloatArray(w * h) { input.readFloat() }
        input.close()
        val original = EdgeSharpness.measure(a, w, h)
        val soft = EdgeSharpness.measure(blur(a, w, h, 3), w, h)
        val softer = EdgeSharpness.measure(blur(a, w, h, 6), w, h)
        println("Photo full-frame sharpness: ${original.value * 100}% (${original.value * 30 / 85 * 100}/35.29); edges=${original.edges}; blur r3=${soft.value * 100}%; r6=${softer.value * 100}%")
        assertTrue(original.reliable && soft.reliable && softer.reliable)
        assertTrue(original.value > soft.value && soft.value > softer.value)
    }
}
