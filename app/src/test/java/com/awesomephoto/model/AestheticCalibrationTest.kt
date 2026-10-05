package com.awesomephoto.model

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AestheticCalibrationTest {
    @Test fun shippedCatalogMustMatchRuntimeCalibration() {
        val catalog = ModelCatalog.parse(File("src/main/assets/models.json").readText())
        for (model in catalog.aesthetics) {
            assertNotNull("Release model has no matching calibration: ${model.id}",
                AestheticCalibration.forModel(model.id, model.sha256))
        }
        // v1.1.1 re-exported these files in CI, so neither matched the calibrated artifacts.
        assertNull(AestheticCalibration.forModel("nima-mobile", "849c6d141b8d8daa83ce4feeb161a266eb3f88cf08c19f99ffabb91ac7082a37"))
        assertNull(AestheticCalibration.forModel("topiq-res50", "cebe2b8e897eb8508d3e56493bf5a72a8941419a5f428076864c8a33cb7cf856"))
    }
    private val nima = AestheticCalibration.forModel("nima-mobile", "b2c69d0e64cae27925c6d6918863b137bec3c89877f4c5276f77d2992baf1722")!!
    private val topiq = AestheticCalibration.forModel("topiq-res50", "a3c8cd79dad650d17451a573997b88389462a0216bee7118af2ccf6eaf42135c")!!

    @Test fun calibrationIsBoundedMonotonicAndSpecificToWeights() {
        for (profile in listOf(nima, topiq)) {
            val values = (100..1000).map { profile.normalize(it / 100.0) }
            assertTrue(values.all { it in 0f..1f })
            assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
            assertEquals(0f, values.first(), 0f)
            assertEquals(1f, values.last(), 0f)
        }
        assertNull(AestheticCalibration.forModel("nima-mobile", topiq.modelSha256))
        assertNull(AestheticCalibration.forModel("new-model", nima.modelSha256))
    }

    @Test fun referenceMeansMatchTheAuditedFitAndExplainTheScale() {
        assertEquals(.773949f, nima.normalize(5.356102876670775), .00001f)
        assertEquals(.78979f, topiq.normalize(5.668786348775029), .0001f)
        val distribution = FloatArray(10).apply { this[4] = .6f; this[5] = .4f }
        val detail = WallpaperAssessment.aesthetic(distribution, "NIMA", nima)
        assertEquals(nima.normalize(5.4), detail.value, .000001f)
        assertEquals(70.0, detail.weight, 0.0)
        assertTrue(detail.policy.contains("不改变模型排序"))
        assertTrue(detail.reason.contains("模型原始均值"))
        assertEquals((5.4f - 1) / 9, WallpaperAssessment.aesthetic(distribution).value, .000001f)
    }
}
