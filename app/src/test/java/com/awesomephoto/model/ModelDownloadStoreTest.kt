package com.awesomephoto.model

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class ModelDownloadStoreTest {
    private val bytes = "verified model".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun model(id: String = "nima-mobile", sha: String = hash) = DownloadableModel(id, id, "aesthetic", "https://invalid.example/model", sha, bytes.size.toLong(), 224, "minus-one-one", 4)

    @Test fun verifiedModelsSurviveSwitchingAndReopeningWithoutNetwork() {
        val directory = Files.createTempDirectory("model-store").toFile()
        try {
            val a = model(); val b = model("second-model")
            directory.resolve(a.fileName).writeBytes(bytes)
            directory.resolve(b.fileName).writeBytes(bytes)
            // Any attempted network request would fail: successful reuse must be local.
            for (choice in listOf(a,b,a)) {
                val file = ModelDownloadStore(directory).prepare(choice)
                assertArrayEquals(bytes, file.readBytes())
            }
            assertEquals(2, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedTruncatedOversizedAndWrongHashNeverPassVerification() {
        val directory = Files.createTempDirectory("model-store").toFile()
        try {
            val store = ModelDownloadStore(directory)
            for (input in listOf(bytes.dropLast(1).toByteArray(), bytes + 0.toByte(), ByteArray(bytes.size))) {
                assertThrows(IllegalStateException::class.java) {
                    store.verifyAndWrite(input.inputStream(), directory.resolve("download.part"), model())
                }
                assertFalse(store.has(model()))
            }
            assertThrows(CancellationException::class.java) {
                store.verifyAndWrite(bytes.inputStream(), directory.resolve("download.part"), model(), { throw CancellationException() })
            }
            assertFalse(store.has(model()))
        } finally { directory.deleteRecursively() }
    }

    @Test fun modelCacheKeysUseContentNotDownloadLocation() {
        val a = model()
        assertEquals(a.cacheIdentity, a.copy(url = "https://other.example/model").cacheIdentity)
        assertNotEquals(a.cacheIdentity, model("different-model").cacheIdentity)
        assertNotEquals(a.cacheIdentity, model(sha = "a".repeat(64)).cacheIdentity)
        assertNotEquals(a.cacheIdentity, a.copy(normalization = "zero-one").cacheIdentity)
    }
}
