package com.vsk.attendance.face

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Why the face is not ready, phrased as one actionable line. Deliberately narrow: an employee who
 * cannot read a sentence must still know what to do with their body.
 */
internal enum class FramingReason(val instruction: FaceInstruction) {
    TooFar(FaceCopy.MOVE_CLOSER),
    TooClose(FaceCopy.MOVE_BACK),
    EyesOut(FaceCopy.FACE_IN_FRAME),
    NoseOut(FaceCopy.FACE_IN_FRAME),
    MouthOut(FaceCopy.FACE_IN_FRAME),
    Tilt(FaceCopy.UPRIGHT),
}

/**
 * Geometry of the 478-point face mesh, kept free of Android and MediaPipe types so the blink and
 * framing rules can be unit tested and validated against a real camera before they reach a phone.
 *
 * Indices are the mesh contract, not tuning knobs: right eye corners 33/133 with lids 159/145,
 * left eye corners 362/263 with lids 386/374. The chin (152) is never required, because a phone
 * held at chest height always crops the jaw and that single condition used to hold a perfectly
 * usable face out of the ready state for seconds.
 */
internal object FaceGeometry {
    const val LANDMARK_COUNT = 478
    val RIGHT_EYE = intArrayOf(33, 133, 159, 145)
    val LEFT_EYE = intArrayOf(362, 263, 386, 374)
    const val OUTER_RIGHT = 33
    const val OUTER_LEFT = 263
    const val NOSE_TIP = 4
    const val MOUTH_RIGHT = 61
    const val MOUTH_LEFT = 291

    const val SPAN_MIN_PX = 48f
    const val SPAN_MAX_FRACTION = 0.42f
    const val ROLL_MAX_DEGREES = 25.0
    const val EYE_MARGIN = 0.14f
    const val NOSE_MARGIN = 0.05f
    const val MOUTH_MARGIN = 0.02f

    /** Normalised x of landmark [index] in a packed [x, y] array. */
    fun x(xy: FloatArray, index: Int): Float = xy[index * 2]

    fun y(xy: FloatArray, index: Int): Float = xy[index * 2 + 1]

    private fun distance(xy: FloatArray, a: Int, b: Int): Float =
        hypot((x(xy, a) - x(xy, b)).toDouble(), (y(xy, a) - y(xy, b)).toDouble()).toFloat()

    /**
     * Eyelid gap divided by eye width for one eye. Around 0.25-0.35 with the eye open and close to
     * zero when shut, so it is the ratio the blink tracker compares against its own baseline.
     */
    fun openRatio(xy: FloatArray, eye: IntArray): Float {
        val horizontal = distance(xy, eye[0], eye[1])
        if (horizontal <= 0f) return 0f
        return min(1f, distance(xy, eye[2], eye[3]) / horizontal)
    }

    /** The more closed of the two eyes decides, so one twitching lid cannot hide a blink. */
    fun eyeOpenness(xy: FloatArray): Float =
        min(openRatio(xy, RIGHT_EYE), openRatio(xy, LEFT_EYE))

    /** Outer corner to outer corner distance, which tracks face size better than a bounding box. */
    fun eyeSpanPx(xy: FloatArray, widthPx: Int): Float = distance(xy, OUTER_RIGHT, OUTER_LEFT) * widthPx

    fun rollDegrees(xy: FloatArray): Double = Math.toDegrees(
        atan2(
            (y(xy, OUTER_LEFT) - y(xy, OUTER_RIGHT)).toDouble(),
            (x(xy, OUTER_LEFT) - x(xy, OUTER_RIGHT)).toDouble()
        )
    )

    private fun inside(xy: FloatArray, index: Int, margin: Float): Boolean {
        val px = x(xy, index)
        val py = y(xy, index)
        return px > margin && px < 1 - margin && py > margin && py < 1 - margin
    }

    private fun eyeCentreOutside(xy: FloatArray, eye: IntArray, margin: Float): Boolean {
        val cx = (x(xy, eye[0]) + x(xy, eye[1])) / 2
        val cy = (y(xy, eye[0]) + y(xy, eye[1])) / 2
        return cx !in margin..1 - margin || cy !in margin..1 - margin
    }

    /** First reason that applies, or null when the face is usable. */
    fun framing(xy: FloatArray, widthPx: Int): FramingReason? {
        val span = eyeSpanPx(xy, widthPx)
        if (span < SPAN_MIN_PX) return FramingReason.TooFar
        if (span > widthPx * SPAN_MAX_FRACTION) return FramingReason.TooClose
        if (eyeCentreOutside(xy, RIGHT_EYE, EYE_MARGIN) || eyeCentreOutside(xy, LEFT_EYE, EYE_MARGIN)) {
            return FramingReason.EyesOut
        }
        if (!inside(xy, NOSE_TIP, NOSE_MARGIN)) return FramingReason.NoseOut
        if (!inside(xy, MOUTH_RIGHT, MOUTH_MARGIN) || !inside(xy, MOUTH_LEFT, MOUTH_MARGIN)) {
            return FramingReason.MouthOut
        }
        if (abs(rollDegrees(xy)) > ROLL_MAX_DEGREES) return FramingReason.Tilt
        return null
    }
}
