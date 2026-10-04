package com.awesomephoto.model

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/** Runs the exact ADE20K semantic model locally. No URI or bitmap leaves the device. */
class SegFormerAnalyzer(modelFile: File) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { environment.createSession(modelFile.absolutePath, it) }

    fun analyzeBatch(bitmaps: List<Bitmap>): List<PhotoScorer.SemanticStats> {
        require(bitmaps.isNotEmpty())
        val size = 512
        val batch = FloatArray(bitmaps.size * 3 * size * size)
        bitmaps.forEachIndexed { imageIndex, bitmap ->
            val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
            try {
                val plane = size * size
                val pixels = IntArray(plane)
                scaled.getPixels(pixels, 0, size, 0, 0, size, size)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    batch[imageIndex * 3 * plane + i] = ((p shr 16 and 255) / 255f - .485f) / .229f
                    batch[(imageIndex * 3 + 1) * plane + i] = ((p shr 8 and 255) / 255f - .456f) / .224f
                    batch[(imageIndex * 3 + 2) * plane + i] = ((p and 255) / 255f - .406f) / .225f
                }
            } finally { if (scaled !== bitmap) scaled.recycle() }
        }
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(batch), longArrayOf(bitmaps.size.toLong(), 3, size.toLong(), size.toLong()))
        tensor.use {
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = result[0].value as Array<Array<Array<FloatArray>>>
                return logits.map { imageLogits ->
                    val outHeight = imageLogits[0].size
                    val outWidth = imageLogits[0][0].size
                    val labels = IntArray(outHeight * outWidth)
                    for (index in labels.indices) {
                        val y = index / outWidth; val x = index % outWidth
                        var bestClass = 0; var bestValue = -Float.MAX_VALUE
                        imageLogits.indices.forEach { clazz ->
                            val value = imageLogits[clazz][y][x]
                            if (value > bestValue) { bestValue = value; bestClass = clazz }
                        }
                        labels[index] = bestClass
                    }
                    PhotoScorer.SemanticStats(labels, outWidth, outHeight)
                }
            }
        }
    }

    override fun close() = session.close()
}
