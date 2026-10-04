package com.awesomephoto.model

import org.junit.Assert.*
import org.junit.Test

class WallpaperAssessmentTest {
    private fun clarity(value: Float) = ScoreDetail("清晰度", 100.0, value, "边缘测量", "主体边缘清楚")

    @Test fun distributionMapsFullRatingScaleWithoutInflation() {
        for (rating in 1..10) {
            val distribution = FloatArray(10) { if (it == rating - 1) 1f else 0f }
            val detail = WallpaperAssessment.aesthetic(distribution)
            assertEquals((rating - 1) / 9f, detail.value, .000001f)
        }
        assertThrows(IllegalArgumentException::class.java) { WallpaperAssessment.aesthetic(FloatArray(10)) }
        assertThrows(IllegalArgumentException::class.java) { WallpaperAssessment.aesthetic(FloatArray(10) { Float.NaN }) }
    }

    @Test fun clearImageCannotOverrideWeakAesthetics() {
        val result = WallpaperAssessment.combine(
            WallpaperAssessment.aesthetic(FloatArray(10) { if (it == 0) 1f else 0f }),
            WallpaperAssessment.subjectStructure(IntArray(100) { 12 }, 10, 10), clarity(1f))
        assertEquals(30, result.total)
        assertTrue(WallpaperAssessment.valid(result.details))
        assertFalse(WallpaperAssessment.valid(listOf(clarity(1f))))
    }

    @Test fun connectedSubjectDoesNotDependOnPositionOrSize() {
        for (offset in listOf(1, 10)) for (size in listOf(3, 5)) {
            val mask = BooleanArray(400) { val x = it % 20; val y = it / 20
                x in offset until offset + size && y in 4 until 4 + size }
            assertEquals(1f, WallpaperAssessment.subjectStructure(IntArray(mask.size) { if (mask[it]) 12 else -1 },20,20).value, 0f)
        }
    }

    @Test fun fragmentedSubjectsAndMissingEvidenceAreDistinct() {
        val mask = BooleanArray(400) { val x = it % 20; val y = it / 20
            x % 4 < 2 && y % 4 < 2 }
        val fragmented = WallpaperAssessment.subjectStructure(IntArray(mask.size) { if (mask[it]) 12 else -1 },20,20)
        assertEquals(3f / 25, fragmented.value, .0001f)
        val absent = WallpaperAssessment.subjectStructure(IntArray(400) { -1 },20,20)
        assertEquals(.5f, absent.value, 0f)
        assertTrue(absent.reason.contains("未检测到"))
    }

    @Test fun subjectScoreDoesNotDependOnCategoryLabel() {
        val values = listOf(0, 12, 132, 149).map { label ->
            WallpaperAssessment.subject(PhotoScorer.SemanticStats(IntArray(100) { label },10,10)).value
        }
        assertTrue(values.all { it == 1f })
    }

    @Test fun rejectsOldCacheAndAvoidsFloatRoundingLosingAPoint() {
        val details = WallpaperAssessment.weights.map { (name, weight) ->
            ScoreDetail(name, weight, .9f, "口径", "依据")
        }
        assertEquals(90, PhotoScorer.Result(details).total)
        assertTrue(WallpaperAssessment.valid(details))
        assertFalse(WallpaperAssessment.valid(details.dropLast(1)))
        assertFalse(WallpaperAssessment.valid(details.map { it.copy(weight = 100.0) }))
        assertFalse(WallpaperAssessment.valid(details.map { it.copy(value = Float.NaN) }))
    }

    @Test fun noRowWrappingConnectsSeparateRegions() {
        val mask = BooleanArray(100)
        for (row in listOf(0, 2, 4, 6)) { mask[row * 10 + 9] = true; mask[(row + 1) * 10] = true }
        assertTrue(WallpaperAssessment.subjectStructure(IntArray(mask.size) { if (mask[it]) 12 else -1 },10,10).reason.contains("证据不足"))
    }
}
