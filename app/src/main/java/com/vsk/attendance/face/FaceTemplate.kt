package com.vsk.attendance.face

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

/**
 * The stored registration: five face-mesh shape prints taken in one supervised session.
 *
 * The envelope is deliberately the same as the previous one - version, model tag, five unit-length
 * samples - so the server's template validator, the encrypted copy on the phone and the activation
 * revision all keep working unchanged. Only the model tag and the sample length moved, from a 128-value
 * SFace embedding to a 78-value MediaPipe mesh print, and that is what makes every older registration
 * unreadable to this build rather than merely different.
 */
@Serializable
data class FaceTemplate(
    @Required val v: Int = 3,
    @Required val model: String = MODEL,
    val samples: List<List<Float>>
) {
    fun encode(): String = json.encodeToString(this)

    fun matches(vector: List<Float>): Boolean {
        if (!validVector(vector)) return false
        val scores = samples.map { cosine(it, vector) }.sortedDescending()
        return scores[1] >= MATCH_THRESHOLD && cosine(centroid(samples), vector) >= MATCH_THRESHOLD
    }

    /** Best of the stored samples against [vector], so a debug build can report the real margin. */
    fun bestScore(vector: List<Float>): Float =
        if (!validVector(vector)) -1f else samples.maxOf { cosine(it, vector) }

    companion object {
        const val MODEL = "mesh-print-v1"
        const val SAMPLE_COUNT = 5
        const val DIMENSIONS = FaceMeshSignature.DIMENSIONS

        /**
         * Every human face has the same layout, so mesh prints of two strangers still sit far above
         * zero cosine while prints of the same person sit very near one. The whole separation is in
         * the last three decimals: same session around 0.9999, an unrelated face around 0.995. This
         * is a shape-similarity operating point that a field test has to confirm, not SFace's
         * published 0.363 embedding threshold, and it is the number the debug line reports.
         */
        const val MATCH_THRESHOLD = 0.999f
        private val json = Json { encodeDefaults = true }

        fun decode(value: String?): FaceTemplate? {
            if (value == null || value.length > 40000) return null
            return runCatching { json.decodeFromString<FaceTemplate>(value) }.getOrNull()?.takeIf {
                it.v == 3 && it.model == MODEL && it.samples.size == SAMPLE_COUNT &&
                    it.samples.all(::validVector)
            }
        }

        fun isCurrent(value: String?): Boolean = decode(value) != null

        fun normalized(vector: List<Float>): List<Float>? {
            if (vector.size != DIMENSIONS || vector.any { !it.isFinite() }) return null
            val norm = sqrt(vector.sumOf { it.toDouble() * it })
            if (norm < 1e-6) return null
            return vector.map { (it / norm).toFloat() }
        }

        fun cosine(a: List<Float>, b: List<Float>): Float {
            if (a.size != DIMENSIONS || b.size != DIMENSIONS) return -1f
            return a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat().coerceIn(-1f, 1f)
        }

        internal fun validVector(vector: List<Float>): Boolean {
            if (vector.size != DIMENSIONS || vector.any { !it.isFinite() }) return false
            val norm = sqrt(vector.sumOf { it.toDouble() * it })
            return norm in 0.98..1.02
        }

        private fun centroid(samples: List<List<Float>>): List<Float> =
            normalized(List(DIMENSIONS) { i -> samples.map { it[i] }.average().toFloat() })
                ?: List(DIMENSIONS) { 0f }
    }
}
