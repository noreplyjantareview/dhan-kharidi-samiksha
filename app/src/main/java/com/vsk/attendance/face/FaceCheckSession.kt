package com.vsk.attendance.face

import kotlin.math.abs

internal data class FaceCheckProgress(
    val message: String,
    val accepted: Int = 0,
    val complete: Boolean = false,
    val template: String? = null
)

internal class FaceCheckSession(
    private val enrolling: Boolean,
    storedTemplate: String?,
    private val turn: String,
    private val startedAt: Long
) {
    private val reference = FaceTemplate.decode(storedTemplate)
    private val samples = mutableListOf<List<Float>>()
    private var anchor: List<Float>? = null
    private var stage = 0
    private var stageFrames = 0
    private var baseline = 0f
    private var lastAcceptedAt = -1L
    private var lastSeenAt = startedAt
    private var finished = false

    init {
        require(enrolling || reference != null) { "Your face registration needs an administrator reset." }
        require(turn == "left" || turn == "right")
    }

    fun rejected(message: String, now: Long, identityFailure: Boolean = false): FaceCheckProgress {
        if (identityFailure || now - lastSeenAt > 1800) reset()
        return FaceCheckProgress(message, samples.size)
    }

    fun accept(vector: List<Float>, yaw: Float, now: Long): FaceCheckProgress {
        if (finished) return FaceCheckProgress("Face check complete", complete = true)
        if (now - startedAt > 65000) return FaceCheckProgress("Face check timed out. Close and try again.")
        if (!FaceTemplate.validVector(vector) || !yaw.isFinite()) {
            return rejected("Face could not be read. Face the camera and retry.", now, true)
        }
        if (now - lastSeenAt > 1800) reset()
        lastSeenAt = now
        if (reference != null && !reference.matches(vector)) {
            return rejected("Face did not match. Face the camera in even light and retry.", now, true)
        }
        if (anchor != null && FaceTemplate.cosine(anchor!!, vector) < FaceTemplate.MATCH_THRESHOLD) {
            return rejected("Keep the same person in view throughout the check.", now, true)
        }
        if (lastAcceptedAt >= 0 && now - lastAcceptedAt < 350) return progress()
        lastAcceptedAt = now
        when (stage) {
            0 -> {
                if (abs(yaw) > 0.16f) return FaceCheckProgress("Look straight at the camera", samples.size)
                if (anchor == null) anchor = vector
                baseline += yaw
                if (enrolling) samples.add(vector)
                stageFrames++
                if (stageFrames == 2) {
                    baseline /= 2f
                    stage = 1
                    stageFrames = 0
                }
            }
            1 -> {
                val movement = (yaw - baseline) * if (turn == "left") 1f else -1f
                if (movement >= 0.18f && movement <= 0.50f) {
                    stageFrames++
                    if (stageFrames == 2) {
                        stage = 2
                        stageFrames = 0
                    }
                } else {
                    stageFrames = 0
                }
            }
            2 -> {
                if (abs(yaw - baseline) > 0.12f || abs(yaw) > 0.18f) {
                    stageFrames = 0
                    if (enrolling) while (samples.size > 2) samples.removeAt(samples.lastIndex)
                    return FaceCheckProgress("Look straight at the camera again", samples.size)
                }
                if (enrolling) samples.add(vector)
                stageFrames++
                if (stageFrames == 3) {
                    finished = true
                    return FaceCheckProgress(
                        "Face check complete", 5, true,
                        if (enrolling) FaceTemplate(samples = samples.toList()).encode() else null
                    )
                }
            }
        }
        return progress()
    }

    private fun progress() = FaceCheckProgress(
        when (stage) {
            0 -> "Look straight at the camera and hold still"
            1 -> "Slowly turn your head slightly to your $turn"
            else -> "Look straight at the camera again and hold still"
        },
        if (enrolling) samples.size else if (stage == 2) 2 + stageFrames else stageFrames
    )

    private fun reset() {
        samples.clear()
        anchor = null
        stage = 0
        stageFrames = 0
        baseline = 0f
        lastAcceptedAt = -1L
    }
}
