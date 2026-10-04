package com.awesomephoto.model

import org.junit.Assert.*
import org.junit.Test

class PhotoCategoryTest {
    private fun scene(vararg regions: Pair<Int, Int>): PhotoScorer.SemanticStats {
        val labels = IntArray(1000) { 3 } // Floor: not itself a subject category.
        var offset = 0
        regions.forEach { (id, count) -> repeat(count) { labels[offset++] = id } }
        return PhotoScorer.SemanticStats(labels, 100, 10)
    }

    @Test fun sculptureWithVisitorsIsArt() {
        val stats = scene(132 to 400, 12 to 20, 1 to 200)
        assertEquals(PhotoKind.ART, stats.category)
        assertTrue(stats.labelNames.contains("雕塑"))
    }

    @Test fun paintingAndBuildingsHaveTheirOwnCategories() {
        assertEquals(PhotoKind.ART, scene(22 to 200).category)
        assertEquals(PhotoKind.ARCHITECTURE, scene(25 to 300, 2 to 500).category)
        assertEquals(PhotoKind.ARCHITECTURE, scene(61 to 150).category)
    }

    @Test fun peopleSceneryAndOtherRemainAvailable() {
        assertEquals(PhotoKind.PERSON, scene(12 to 200, 2 to 400).category)
        assertEquals(PhotoKind.SCENERY, scene(4 to 200, 9 to 200).category)
        assertEquals(PhotoKind.OTHER, scene(20 to 700).category)
        assertEquals(5, PhotoKind.entries.count { it != PhotoKind.ALL })
    }

    @Test fun tinyArtRegionDoesNotOverridePortrait() {
        assertEquals(PhotoKind.PERSON, scene(22 to 80, 12 to 300).category)
        assertEquals(PhotoKind.ART, scene(22 to 81, 12 to 300).category)
    }
}
