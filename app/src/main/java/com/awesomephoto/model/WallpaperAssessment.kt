package com.awesomephoto.model

import java.util.Locale
import kotlin.math.abs

/** Offline aesthetic prediction and an explicitly approximate subject-structure measure. */
object WallpaperAssessment {
    const val RULE_VERSION = "local-nima-wallpaper-v1"
    val weights = linkedMapOf("画面美感" to 70.0, "主体明确度" to 20.0, "清晰度" to 10.0)

    fun aesthetic(ratings: FloatArray, modelName: String = "NIMA MobileNet"): ScoreDetail {
        require(ratings.size == 10 && ratings.all { it.isFinite() && it in 0f..1f }) { "审美模型输出无效" }
        val sum = ratings.sum()
        require(abs(sum - 1f) < .001f) { "审美模型概率分布无效" }
        val mean = ratings.indices.sumOf { (it + 1) * (ratings[it] / sum).toDouble() }
        return ScoreDetail("画面美感", 70.0, ((mean - 1.0) / 9.0).toFloat().coerceIn(0f, 1f),
            "本地 $modelName 预测 1–10 分审美分布，均值按 (均值−1)÷9 映射为百分制；用于相对排序，不是及格率。",
            String.format(Locale.ROOT, "模型预测审美均值 %.2f/10。依据整张照片的学习特征评价整体观感；模型不能生成可信的逐项色彩、光影解释，也不理解你的个人回忆。", mean))
    }

    fun subject(semantics: PhotoScorer.SemanticStats): ScoreDetail =
        subjectStructure(semantics.labels, semantics.width, semantics.height)

    internal fun subjectStructure(labels: IntArray, width: Int, height: Int): ScoreDetail {
        require(width > 0 && height > 0 && labels.size == width * height)
        val area = labels.count { it >= 0 }
        val policy = "用全图语义区域的集中连贯性近似主体明确度：最大三个连通区域占有效区域的比例。忽略小于全图 0.1% 的分割碎片；不按主体位置或题材类别加分，分类为其他也照常测量。此项不理解故事或元素的语义关系。"
        if (area < maxOf(4, labels.size / 100)) {
            return ScoreDetail("主体明确度", 20.0, .5f, policy, "未检测到足够画面区域，暂按中性 50% 计分，不据此判断主题不明确。")
        }
        val seen = BooleanArray(labels.size)
        val queue = IntArray(labels.size)
        val regions = mutableListOf<Int>()
        for (start in labels.indices) {
            if (labels[start] < 0 || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val i = queue[head++]
                fun visit(next: Int) {
                    if (labels[next] == labels[start] && !seen[next]) { seen[next] = true; queue[tail++] = next }
                }
                if (i % width > 0) visit(i - 1)
                if (i % width < width - 1) visit(i + 1)
                if (i >= width) visit(i - width)
                if (i < labels.size - width) visit(i + width)
            }
            if (tail >= maxOf(2, labels.size / 1000)) regions += tail
        }
        if (regions.isEmpty()) return ScoreDetail("主体明确度", 20.0, .5f, policy, "画面分割只有细小碎片，证据不足，暂按中性 50% 计分。")
        val fraction = regions.sortedDescending().take(3).sum().toFloat() / regions.sum()
        return ScoreDetail("主体明确度", 20.0, fraction, policy,
            String.format(Locale.ROOT, "检测到 %d 个有效画面区域，最大三个占 %.1f%%。仅反映画面区域是否集中连贯，不等同于人工判断主题。", regions.size, fraction * 100))
    }

    fun combine(aesthetic: ScoreDetail, subject: ScoreDetail, clarity: ScoreDetail): PhotoScorer.Result {
        val details = listOf(aesthetic, subject, clarity.copy(weight = 10.0))
        require(valid(details)) { "壁纸评分缺少有效维度" }
        return PhotoScorer.Result(details)
    }

    fun valid(details: List<ScoreDetail>): Boolean = details.size == weights.size &&
        details.map { it.name } == weights.keys.toList() && details.all {
            it.weight == weights[it.name] && it.value.isFinite() && it.value in 0f..1f &&
                it.policy.isNotBlank() && it.reason.isNotBlank()
        }
}
