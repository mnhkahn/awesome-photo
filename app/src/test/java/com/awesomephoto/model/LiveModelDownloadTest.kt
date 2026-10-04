package com.awesomephoto.model

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LiveModelDownloadTest {
    @Test fun downloadVerifiedModelsThenReuseThemWithNoNetwork() {
        val path = System.getenv("LIVE_MODEL_CATALOG")
        assumeTrue(!path.isNullOrBlank())
        val catalog = ModelCatalog.parse(File(path!!).readText())
        val directory = Files.createTempDirectory("live-model-download").toFile()
        try {
            val store = ModelDownloadStore(directory)
            val selected = listOf(catalog.aesthetic("nima-mobile"), catalog.segmentation)
            selected.forEach { model ->
                val file = store.prepare(model)
                assertEquals(model.bytes, file.length())
                println("Verified remote model ${model.id}: ${file.length()} bytes")
            }
            selected.reversed().forEach { model ->
                val file = ModelDownloadStore(directory).prepare(model.copy(url = "https://invalid.example/offline"))
                assertEquals(model.bytes, file.length())
            }
            assertEquals(2, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
}
