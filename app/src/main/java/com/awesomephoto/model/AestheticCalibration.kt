package com.awesomephoto.model

/** Initial score-scale fit to 46 visually labelled photos, checked on 11 held-out photos.
 * This calibrates the score scale, not personal memories or the model's ranking.
 * See tools/aesthetic-calibration-report.json. Model hashes prevent applying a fit to new weights.
 */
object AestheticCalibration {
    const val VERSION = "teacher-wallpaper-v1"
    data class Profile(val modelId: String, val modelSha256: String, val slope: Double, val intercept: Double) {
        fun normalize(mean: Double): Float {
            require(mean.isFinite() && mean in 1.0..10.0)
            return ((slope * mean + intercept).coerceIn(0.0, 100.0) / 100.0).toFloat()
        }
    }

    private val profiles = listOf(
        Profile("nima-mobile", "b2c69d0e64cae27925c6d6918863b137bec3c89877f4c5276f77d2992baf1722",
            21.10104915678697, -35.624448783070505),
        Profile("topiq-res50", "a3c8cd79dad650d17451a573997b88389462a0216bee7118af2ccf6eaf42135c",
            18.416471611023983, -25.420048078193922),
    )

    fun forModel(id: String, sha256: String): Profile? =
        profiles.firstOrNull { it.modelId == id && it.modelSha256 == sha256 }
}
