package com.awesomephoto.model

import org.junit.Assert.*
import org.junit.Test

class ClarityScoreTest {
    @Test fun scoreContainsOnlyClarityAndIgnoresSubjectPositionAndCategory() {
        val width = 128; val height = 128
        for (label in listOf(0, 1, 2, 12, 132)) {
            val semantics = PhotoScorer.SemanticStats(IntArray(width * height) { label }, width, height)
            for (center in listOf(32, 64, 96)) {
                val luma = FloatArray(width * height) { if (it % width < center) .3f else .7f }
                val result = PhotoScorer.scoreSample(luma, width, height, semantics)
                assertEquals(100, result.total)
                assertEquals(1, result.details.size)
                assertEquals("清晰度", result.details.single().name)
                assertEquals(100.0, result.details.single().weight, 0.0)
            }
        }
    }

    @Test fun flatImageExplicitlyReportsInsufficientEvidence() {
        val result = PhotoScorer.scoreSample(FloatArray(128 * 128) { .9f }, 128, 128,
            PhotoScorer.SemanticStats(IntArray(128 * 128), 128, 128))
        assertEquals(50, result.total)
        assertTrue(result.details.single().reason.contains("证据不足"))
    }

    @Test fun sharpBackgroundCannotOverrideReliableSoftPersonRegion() {
        val w = 288; val h = 384
        val sharp = FloatArray(w * h) { if (it % w < w / 2) .2f else .8f }
        val soft = FloatArray(sharp.size) { i ->
            (-4..4).sumOf { d -> sharp[(i / w) * w + (i % w + d).coerceIn(0, w - 1)].toDouble() }.toFloat() / 9
        }
        val luma = FloatArray(sharp.size) { if (it / w < h / 2) sharp[it] else soft[it] }
        val labels = IntArray(sharp.size) { if (it / w > h / 2 + 16) 12 else 0 }
        val detail = PhotoScorer.scoreSample(luma, w, h, PhotoScorer.SemanticStats(labels, w, h)).details.single()
        assertTrue(detail.value < .7f)
        assertTrue(detail.reason.contains("按人物区域计分"))
    }

    @Test fun suppliedShallowDepthOfFieldAndBlurControlWhenProvided() {
        val folder = System.getenv("DEPTH_OF_FIELD_TEST_SAMPLES")
        org.junit.Assume.assumeTrue(!folder.isNullOrBlank())
        fun clarity(index: Int): Float = java.io.DataInputStream(java.io.File(folder, "$index.bin").inputStream().buffered()).use { input ->
            val w = input.readInt(); val h = input.readInt(); val sw = input.readInt(); val sh = input.readInt()
            val luma = FloatArray(w * h) { input.readFloat() }
            val labels = IntArray(sw * sh) { input.readInt() }
            PhotoScorer.scoreSample(luma, w, h, PhotoScorer.SemanticStats(labels, sw, sh)).details.single().value
        }
        assertEquals(1f, clarity(0), 0f)
        assertTrue(clarity(1) < .7f)
    }

    @Test fun suppliedPhotosWhenProvided() {
        val folder = System.getenv("CLARITY_TEST_SAMPLES")
        org.junit.Assume.assumeTrue(!folder.isNullOrBlank())
        for (name in listOf("portrait", "sculpture")) {
            java.io.DataInputStream(java.io.File(folder, "$name.bin").inputStream().buffered()).use { input ->
                val w = input.readInt(); val h = input.readInt()
                val sw = input.readInt(); val sh = input.readInt()
                val luma = FloatArray(w * h) { input.readFloat() }
                val labels = IntArray(sw * sh) { input.readInt() }
                val semantics = PhotoScorer.SemanticStats(labels, sw, sh)
                val result = PhotoScorer.scoreSample(luma, w, h, semantics)
                assertEquals(1, result.details.size)
                assertTrue(result.total in 0..100)
                println("$name: ${result.total}/100, category=${semantics.category.label}; ${result.details.single().reason}")
            }
        }
    }
}
