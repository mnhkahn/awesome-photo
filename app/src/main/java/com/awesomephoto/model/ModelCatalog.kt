package com.awesomephoto.model

import android.content.Context
import org.json.JSONObject

/** Bundled metadata only. Weights are downloaded separately and verified by content hash. */
data class DownloadableModel(
    val id: String, val name: String, val role: String, val url: String,
    val sha256: String, val bytes: Long, val inputSize: Int,
    val normalization: String, val batchSize: Int,
) {
    init {
        require(id.matches(Regex("[a-z0-9-]+")))
        require(url.startsWith("https://"))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(bytes > 0 && inputSize in 1..1024 && batchSize in 1..4)
        require(role in setOf("aesthetic", "segmentation"))
        require(normalization in setOf("minus-one-one", "zero-one", "imagenet"))
        if (role == "segmentation") require(inputSize == 512 && normalization == "imagenet")
        else require(normalization != "imagenet")
    }
    val fileName: String get() = "$id-$sha256.onnx"
    val cacheIdentity: String get() = "$id:$sha256:$inputSize:$normalization"
    val sizeMiB: Double get() = bytes / 1048576.0
}

class ModelCatalog(val models: List<DownloadableModel>) {
    init {
        require(models.map { it.id }.distinct().size == models.size)
        require(models.count { it.role == "segmentation" } == 1)
        require(models.any { it.role == "aesthetic" })
    }
    val aesthetics get() = models.filter { it.role == "aesthetic" }
    val segmentation get() = models.single { it.role == "segmentation" }
    fun aesthetic(id: String) = aesthetics.firstOrNull { it.id == id } ?: aesthetics.first()

    companion object {
        fun load(context: Context) = parse(context.assets.open("models.json").bufferedReader().use { it.readText() })
        fun parse(json: String): ModelCatalog {
            val root = JSONObject(json)
            require(root.getInt("schema") == 1)
            val rows = root.getJSONArray("models")
            return ModelCatalog(List(rows.length()) { index ->
                val r = rows.getJSONObject(index)
                DownloadableModel(r.getString("id"), r.getString("name"), r.getString("role"), r.getString("url"),
                    r.getString("sha256"), r.getLong("bytes"), r.getInt("inputSize"), r.getString("normalization"), r.getInt("batchSize"))
            })
        }
    }
}
