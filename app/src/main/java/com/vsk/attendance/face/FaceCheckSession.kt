package com.vsk.attendance.face

import kotlin.math.abs

internal data class FaceCheckProgress(
    val message: FaceInstruction,
    val accepted: Int = 0,
    val complete: Boolean = false,
    val template: String? = null,
    /**
     * Mesh similarity that produced this result, or -1 when no comparison ran. A shape print is only
     * as good as its operating point, so a debug screen carries the number and one field test is what
     * sets [FaceTemplate.MATCH_THRESHOLD] instead of a guess.
     */
    val score: Float = -1f
)

/**
 * Why a frame added no evidence. Impostor signals must restart immediately; a phone that takes
 * more than a second per frame must not, or no employee could ever finish registration.
 */
internal enum class FaceInterruption {
    Quality,
    FaceLost,
    Identity
}

/** What the employee is being asked to do. One stage, one short line. */
internal enum class FaceStage { Align, Blink, Capture, Done }

/** Everything the mesh model knows about the current frame, before identity is looked at. */
internal data class FaceGuidance(
    val openRatio: Float,
    val expressionClosure: Float,
    val framing: FramingReason? = null
)

/** One mesh pass: the guidance for the screen and the shape print for identity. */
internal data class FaceReading(val guidance: FaceGuidance, val signature: MeshSignature)

/**
 * Blink-guided face check, replacing the head-turn sequence that field staff could not follow.
 *
 * Stages: two centred frames build the identity anchor, one blink proves a live person is driving
 * the camera, then three frames taken with the eye open complete the five-sample template. Evidence
 * frames are the only ones whose shape print is measured, so the light and sharpness gate runs with
 * them and the wait between opening the screen and finishing is measured in seconds.
 *
 * Nothing about the stored envelope changed: still five unit-length normalized samples, now 78 mesh
 * values instead of 128 SFace values, which is a new model tag rather than a new server design.
 */
internal class FaceCheckSession(
    private val enrolling: Boolean,
    storedTemplate: String?,
    private val startedAt: Long
) {
    private val reference = FaceTemplate.decode(storedTemplate)
    private val samples = mutableListOf<List<Float>>()
    private val blink = BlinkTracker()
    private var anchor: List<Float>? = null
    private var stage = FaceStage.Align
    private var alignFrames = 0
    private var captureSamples = 0
    private var anchoredSamples = 0
    private var captureStartedAt = 0L
    private var closedStreak = 0
    private var lastAcceptedAt = -1L
    private var lastSeenAt = startedAt
    private var framingFailedSince = 0L
    private var lastClosure = 1f
    private var lastFraming: FramingReason? = FramingReason.EyesOut
    private var lostStreak = 0
    private var blinkPeak = 0f
    private var finished = false

    val currentStage: FaceStage get() = stage

    /** Peak closure of the blink that unlocked sampling, minus the threshold: diagnostic margin. */
    val blinkMargin: Float get() = blinkPeak - BlinkTracker.CLOSE_T

    /**
     * A half-shut eyelid moves the lids the print measures, so a frame only contributes identity
     * while the face is framed and the eyes say it is open.
     */
    val needsEvidence: Boolean
        get() = (stage == FaceStage.Align || stage == FaceStage.Capture) && eyesReady

    private val eyesReady: Boolean
        get() = lastFraming == null && lastClosure < BlinkTracker.OPEN_T

    init {
        require(enrolling || reference != null) { "Your face registration needs an administrator reset." }
    }

    /** Every frame goes through here first; it is cheap and decides what the print is used for. */
    fun onGuidance(guide: FaceGuidance, now: Long): FaceCheckProgress {
        if (finished) return FaceCheckProgress(FaceCopy.COMPLETE, SAMPLES_REQUIRED, complete = true)
        if (now - startedAt > TIMEOUT_MS) return FaceCheckProgress(FaceCopy.TIMED_OUT, samples.size)
        val absent = now - lastSeenAt > PRESENCE_GAP_MS
        lastSeenAt = now
        if (absent) reset()
        val reading = blink.observe(guide.openRatio, guide.expressionClosure, guide.framing == null, now)
        lastClosure = reading.closure
        lastFraming = guide.framing
        if (guide.framing != null) {
            framingFailedSince = if (framingFailedSince == 0L) now else framingFailedSince
            if (now - framingFailedSince < FAIL_QUIET_MS) return progress()
            if (stage == FaceStage.Capture) abortCapture()
            return FaceCheckProgress(guide.framing.instruction, samples.size)
        }
        framingFailedSince = 0L
        if (reading.eyeClosed) {
            closedStreak++
            if (closedStreak > CLOSED_FRAMES_MAX && stage == FaceStage.Capture) abortCapture()
            return progress()
        }
        closedStreak = 0
        if (stage == FaceStage.Capture && now - captureStartedAt > CAPTURE_WINDOW_MS) abortCapture()
        if (stage == FaceStage.Blink && reading.blink) {
            blinkPeak = reading.peakClosure
            stage = FaceStage.Capture
            captureStartedAt = now
            captureSamples = 0
            anchoredSamples = samples.size
        }
        return progress()
    }

    /** A frame whose mesh print was taken after the light and sharpness gate passed. */
    fun onEvidence(vector: List<Float>, yaw: Float, now: Long): FaceCheckProgress {
        val eyesWereReady = eyesReady
        if (finished) return FaceCheckProgress(FaceCopy.COMPLETE, SAMPLES_REQUIRED, complete = true)
        if (now - startedAt > TIMEOUT_MS) return FaceCheckProgress(FaceCopy.TIMED_OUT, samples.size)
        if (!FaceTemplate.validVector(vector) || !yaw.isFinite()) {
            return rejected(FaceCopy.UNREADABLE, now, FaceInterruption.Identity)
        }
        if (reference != null && !reference.matches(vector)) {
            return rejected(FaceCopy.NO_MATCH, now, FaceInterruption.Identity)
                .copy(score = reference.bestScore(vector))
        }
        if (anchor != null) {
            val anchored = FaceTemplate.cosine(anchor!!, vector)
            if (anchored < FaceTemplate.MATCH_THRESHOLD) {
                return rejected(FaceCopy.ONE_PERSON, now, FaceInterruption.Identity).copy(score = anchored)
            }
        }
        if (now - lastSeenAt > PRESENCE_GAP_MS) reset()
        lastSeenAt = now
        lostStreak = 0
        if (!eyesWereReady) return progress()
        if (lastAcceptedAt >= 0 && now - lastAcceptedAt < EVIDENCE_SPACING_MS) return progress()
        if (abs(yaw) > CENTER_YAW) return FaceCheckProgress(FaceCopy.LOOK, samples.size)
        lastAcceptedAt = now
        when (stage) {
            FaceStage.Align -> {
                if (anchor == null) anchor = vector
                if (enrolling) samples.add(vector)
                alignFrames++
                if (alignFrames >= ALIGN_SAMPLES) stage = FaceStage.Blink
            }
            FaceStage.Capture -> {
                if (enrolling) samples.add(vector)
                captureSamples++
                if (captureSamples >= FINAL_SAMPLES) {
                    finished = true
                    stage = FaceStage.Done
                    return FaceCheckProgress(
                        FaceCopy.COMPLETE, SAMPLES_REQUIRED, true,
                        if (enrolling) FaceTemplate(samples = samples.take(SAMPLES_REQUIRED)).encode() else null
                    )
                }
            }
            else -> Unit
        }
        return progress()
    }

    fun rejected(
        instruction: FaceInstruction,
        now: Long,
        interruption: FaceInterruption = FaceInterruption.Quality
    ): FaceCheckProgress {
        val absent = now - lastSeenAt > PRESENCE_GAP_MS
        lastSeenAt = now
        when {
            interruption == FaceInterruption.Identity || absent -> reset()
            interruption == FaceInterruption.FaceLost -> {
                lostStreak++
                if (lostStreak >= FACE_LOST_FRAMES) reset()
            }
            else -> lostStreak = 0
        }
        return FaceCheckProgress(instruction, samples.size)
    }

    private fun abortCapture() {
        if (enrolling) while (samples.size > anchoredSamples) samples.removeAt(samples.lastIndex)
        captureSamples = 0
        closedStreak = 0
        stage = FaceStage.Blink
    }

    private fun progress() = FaceCheckProgress(messageFor(stage), if (enrolling) samples.size else 0)

    private fun messageFor(current: FaceStage): FaceInstruction = when (current) {
        FaceStage.Align -> FaceCopy.LOOK
        FaceStage.Blink -> FaceCopy.BLINK_NOW
        FaceStage.Capture -> FaceCopy.KEEP_LOOKING
        FaceStage.Done -> FaceCopy.COMPLETE
    }

    private fun reset() {
        samples.clear()
        anchor = null
        stage = FaceStage.Align
        alignFrames = 0
        captureSamples = 0
        anchoredSamples = 0
        closedStreak = 0
        framingFailedSince = 0L
        lastAcceptedAt = -1L
        lostStreak = 0
        blinkPeak = 0f
        lastClosure = 1f
        lastFraming = FramingReason.EyesOut
        blink.reset()
    }

    companion object {
        const val TIMEOUT_MS = 120_000L

        /** Frames arrive continuously while a face is in view, so silence this long means the person left. */
        const val PRESENCE_GAP_MS = 4_000L

        const val EVIDENCE_SPACING_MS = 350L
        const val FACE_LOST_FRAMES = 4
        const val CENTER_YAW = 0.22f

        /** Frames the eye must stay shut before it counts as a squeeze rather than a blink. */
        const val CLOSED_FRAMES_MAX = 4

        /** A framing complaint only appears once it has been true this long, so jitter stays silent. */
        const val FAIL_QUIET_MS = 600L

        /** Longest a post-blink burst may take before the employee is asked to blink again. */
        const val CAPTURE_WINDOW_MS = 4_000L

        const val ALIGN_SAMPLES = 2
        const val FINAL_SAMPLES = 3
        const val SAMPLES_REQUIRED = ALIGN_SAMPLES + FINAL_SAMPLES
    }
}
