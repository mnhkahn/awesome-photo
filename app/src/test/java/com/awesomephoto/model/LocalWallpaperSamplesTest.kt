package com.awesomephoto.model

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

class LocalWallpaperSamplesTest {
    @Test fun runSuppliedSamplesThroughProductionScoring() {
        val folder = System.getenv("WALLPAPER_TEST_SAMPLES")
        assumeTrue(!folder.isNullOrBlank())
        val samples = File(folder!!).listFiles { file -> file.extension == "bin" }!!.sortedBy { it.name }
        assertTrue(samples.isNotEmpty())
        for (sample in samples) {
            DataInputStream(sample.inputStream().buffered()).use { input ->
                val w=input.readInt(); val h=input.readInt(); val sw=input.readInt(); val sh=input.readInt()
                val luma=FloatArray(w*h) { input.readFloat() }
                val labels=IntArray(sw*sh) { input.readInt() }
                val ratings=FloatArray(10) { input.readFloat() }
                val stats=PhotoScorer.SemanticStats(labels,sw,sh)
                val start=System.nanoTime()
                val clarity=PhotoScorer.scoreSample(luma,w,h,stats).details.single()
                val result=WallpaperAssessment.combine(WallpaperAssessment.aesthetic(ratings),WallpaperAssessment.subject(stats),clarity)
                assertTrue(WallpaperAssessment.valid(result.details))
                println("${sample.name}: total=${result.total}, category=${stats.category.label}, scoringMs=${(System.nanoTime()-start)/1_000_000.0}")
                println("AUDIT_SCORE ${sample.nameWithoutExtension} ${result.details.sumOf { it.points }}")
                result.details.forEach { println("  ${it.name}: ${it.points}/${it.weight}; ${it.reason}") }
            }
        }
    }
}
