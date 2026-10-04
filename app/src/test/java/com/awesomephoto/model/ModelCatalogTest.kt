package com.awesomephoto.model

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    private fun model(id: String, role: String = "aesthetic") = DownloadableModel(id, id, role,
        "https://example.test/$id.onnx", "a".repeat(64), 1234,
        if (role == "segmentation") 512 else 224, if (role == "segmentation") "imagenet" else "zero-one", 1)

    @Test fun catalogSelectsRememberedIdAndFallsBackOnlyWhenRemoved() {
        val catalog = ModelCatalog(listOf(model("light"), model("standard"), model("semantic", "segmentation")))
        assertEquals("standard", catalog.aesthetic("standard").id)
        assertEquals("light", catalog.aesthetic("removed-model").id)
    }

    @Test fun duplicateIdsAndIncompatiblePreprocessingAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalog(listOf(model("same"), model("same"), model("semantic", "segmentation")))
        }
        assertThrows(IllegalArgumentException::class.java) { model("light").copy(normalization = "imagenet") }
        assertThrows(IllegalArgumentException::class.java) { model("semantic", "segmentation").copy(inputSize = 224) }
    }
}
