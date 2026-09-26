package com.awesomephoto.model

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileNotFoundException
import java.nio.FloatBuffer

/** Runs the exact ADE20K semantic model locally. No URI or bitmap leaves the device. */
class SegFormerAnalyzer(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelFile = File(context.cacheDir, "segformer_b0_ade512_int8.onnx")
        if (!modelFile.exists() || modelFile.length() == 0L) {
            val input = try {
                context.assets.open(modelFile.name)
            } catch (error: FileNotFoundException) {
                throw IllegalStateException("安装包缺少照片分析模型，请安装包含完整模型的新版本", error)
            }
            input.use { source ->
                val temporary = File.createTempFile("segformer-", ".tmp", context.cacheDir)
                try {
                    temporary.outputStream().use { source.copyTo(it) }
                    check(temporary.length() > 0L) { "安装包中的照片分析模型为空，请重新安装完整版本" }
                    check(temporary.renameTo(modelFile)) { "无法保存照片分析模型，请检查设备存储空间" }
                } finally {
                    temporary.delete()
                }
            }
        }
        session = OrtSession.SessionOptions().use { environment.createSession(modelFile.absolutePath, it) }
    }

    fun analyzeBatch(bitmaps: List<Bitmap>): List<PhotoScorer.SemanticStats> {
        require(bitmaps.isNotEmpty())
        val size = 512
        val batch = FloatArray(bitmaps.size * 3 * size * size)
        bitmaps.forEachIndexed { imageIndex, bitmap ->
            val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
            val pixels = IntArray(size * size)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            for (pixelIndex in pixels.indices) {
                val p = pixels[pixelIndex]
                val rgb = floatArrayOf((p shr 16 and 0xff) / 255f, (p shr 8 and 0xff) / 255f, (p and 0xff) / 255f)
                val mean = floatArrayOf(.485f, .456f, .406f)
                val std = floatArrayOf(.229f, .224f, .225f)
                for (channel in 0..2) batch[(imageIndex * 3 + channel) * size * size + pixelIndex] = (rgb[channel] - mean[channel]) / std[channel]
            }
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
