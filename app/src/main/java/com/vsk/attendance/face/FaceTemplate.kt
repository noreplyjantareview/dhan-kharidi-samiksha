package com.vsk.attendance.face

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

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

    companion object {
        const val MODEL = "sface-2021dec"
        const val SAMPLE_COUNT = 5
        const val DIMENSIONS = 128
        // SFace's published cosine operating point is a starting point, not a deployment accuracy guarantee.
        const val MATCH_THRESHOLD = 0.363f
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
