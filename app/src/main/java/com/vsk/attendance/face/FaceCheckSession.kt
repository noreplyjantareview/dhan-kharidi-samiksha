package com.vsk.attendance.face

import kotlin.math.abs

internal data class FaceCheckProgress(
    val message: String,
    val accepted: Int = 0,
    val complete: Boolean = false,
    val template: String? = null
)

/**
 * Why a frame added no evidence. Impostor signals must restart immediately; a phone that takes
 * more than a second per frame must not, or no employee could ever finish registration.
 */
internal enum class FaceInterruption {
    Quality,
    FaceLost,
    Pad,
    Identity
}

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
    private var lostStreak = 0
    private var padStreak = 0
    private var finished = false

    init {
        require(enrolling || reference != null) { "Your face registration needs an administrator reset." }
        require(turn == "left" || turn == "right")
    }

    fun rejected(
        message: String,
        now: Long,
        interruption: FaceInterruption = FaceInterruption.Quality
    ): FaceCheckProgress {
        val absent = now - lastSeenAt > PRESENCE_GAP_MS
        lastSeenAt = now
        when {
            interruption == FaceInterruption.Identity || absent -> reset()
            interruption == FaceInterruption.FaceLost -> {
                padStreak = 0
                lostStreak++
                if (lostStreak >= FACE_LOST_FRAMES) reset()
            }
            interruption == FaceInterruption.Pad -> {
                lostStreak = 0
                padStreak++
                if (padStreak >= PAD_FRAMES) reset()
            }
            else -> {
                lostStreak = 0
                padStreak = 0
            }
        }
        return FaceCheckProgress(message, samples.size)
    }

    fun accept(vector: List<Float>, yaw: Float, now: Long): FaceCheckProgress {
        if (finished) return FaceCheckProgress("Face check complete", complete = true)
        if (now - startedAt > TIMEOUT_MS) {
            return FaceCheckProgress("Face check timed out. Start again and follow the on-screen steps.")
        }
        if (!FaceTemplate.validVector(vector) || !yaw.isFinite()) {
            return rejected("Face could not be read. Face the camera and retry.", now, FaceInterruption.Identity)
        }
        val absent = now - lastSeenAt > PRESENCE_GAP_MS
        lastSeenAt = now
        lostStreak = 0
        padStreak = 0
        if (absent) reset()
        if (reference != null && !reference.matches(vector)) {
            return rejected("Face did not match. Face the camera in even light and retry.", now, FaceInterruption.Identity)
        }
        if (anchor != null && FaceTemplate.cosine(anchor!!, vector) < FaceTemplate.MATCH_THRESHOLD) {
            return rejected("Keep the same person in view throughout the check.", now, FaceInterruption.Identity)
        }
        if (lastAcceptedAt >= 0 && now - lastAcceptedAt < EVIDENCE_SPACING_MS) return progress()
        lastAcceptedAt = now
        when (stage) {
            0 -> {
                if (abs(yaw) > CENTER_YAW) return FaceCheckProgress("Look straight at the camera", samples.size)
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
                if (movement >= TURN_MIN && movement <= TURN_MAX) {
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
                if (abs(yaw - baseline) > RETURN_TOLERANCE || abs(yaw) > CENTER_YAW) {
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
        lostStreak = 0
        padStreak = 0
    }

    companion object {
        const val TIMEOUT_MS = 120_000L

        /** Frames arrive continuously while a face is in view, so silence this long means the person left. */
        const val PRESENCE_GAP_MS = 4_000L

        const val EVIDENCE_SPACING_MS = 350L
        const val FACE_LOST_FRAMES = 4
        const val PAD_FRAMES = 3
        const val CENTER_YAW = 0.22f
        const val RETURN_TOLERANCE = 0.16f
        const val TURN_MIN = 0.15f
        const val TURN_MAX = 0.55f
    }
}
