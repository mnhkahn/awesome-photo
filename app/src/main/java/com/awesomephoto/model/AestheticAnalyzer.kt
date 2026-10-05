package com.awesomephoto.model

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/** Downloaded models run entirely locally. */
class AestheticAnalyzer(file: File, private val model: DownloadableModel) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use {
        it.setIntraOpNumThreads(2)
        environment.createSession(file.absolutePath, it)
    }

    fun analyzeBatch(bitmaps: List<Bitmap>): List<ScoreDetail> {
        require(bitmaps.isNotEmpty())
        return bitmaps.chunked(model.batchSize).flatMap(::analyzeChunk)
    }

    private fun analyzeChunk(bitmaps: List<Bitmap>): List<ScoreDetail> {
        val size = model.inputSize
        val plane = size * size
        val batch = FloatArray(bitmaps.size * 3 * plane)
        bitmaps.forEachIndexed { index, bitmap ->
            // NIMA training uses a 224 square input. Whole-frame resize preserves all content.
            val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
            try {
                val pixels = IntArray(plane)
                scaled.getPixels(pixels, 0, size, 0, 0, size, size)
                fun normalized(value: Int) = if (model.normalization == "minus-one-one") value / 127.5f - 1f else value / 255f
                pixels.forEachIndexed { i, p ->
                    batch[index * 3 * plane + i] = normalized(p shr 16 and 255)
                    batch[(index * 3 + 1) * plane + i] = normalized(p shr 8 and 255)
                    batch[(index * 3 + 2) * plane + i] = normalized(p and 255)
                }
            } finally { if (scaled !== bitmap) scaled.recycle() }
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(batch), longArrayOf(bitmaps.size.toLong(), 3, size.toLong(), size.toLong())).use { tensor ->
            session.run(mapOf("rgb" to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val ratings = output[0].value as Array<FloatArray>
                check(ratings.size == bitmaps.size) { "审美模型返回数量错误" }
                val calibration = AestheticCalibration.forModel(model.id, model.sha256)
                return ratings.map { WallpaperAssessment.aesthetic(it, model.name, calibration) }
            }
        }
    }

    override fun close() = session.close()
}
