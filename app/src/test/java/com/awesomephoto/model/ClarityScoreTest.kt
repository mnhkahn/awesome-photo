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
