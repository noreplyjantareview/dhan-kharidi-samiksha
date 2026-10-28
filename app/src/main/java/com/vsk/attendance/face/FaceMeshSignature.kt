package com.vsk.attendance.face

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Identity read from the face mesh alone: the shape of the face, with position, head size, roll and
 * pixel format removed.
 *
 * MediaPipe builds its 468 points from one canonical mesh, so point [index] is the same anatomical
 * location on every face and in every frame. Sampling it on a fixed stride therefore compares
 * like with like, and it keeps this code free of a hand-written table of anatomy that could be
 * wrong without ever failing a test.
 *
 * This is a shape print, not a face embedding: it measures proportions, so it separates most
 * strangers but no two biometric systems are the same and it cannot promise SFace's error rate.
 *
 * [region] is where those sampled points sit in the frame, which is the only part of the image worth
 * metering for light and sharpness.
 */
internal data class MeshSignature(
    val vector: List<Float>,
    val yaw: Float,
    val region: FrameRegion
)

/** Normalized frame coordinates of the face, as the mesh saw it. */
internal data class FrameRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal object FaceMeshSignature {
    /** Landmarks are taken every [STRIDE] index, so 39 points become 78 numbers. */
    const val STRIDE = 12
    const val POINTS = 39
    const val DIMENSIONS = POINTS * 2

    /**
     * The mesh is either the 468-point or the 478-point model, so the print only claims the stride
     * indices it actually reads.
     */
    private const val MIN_LANDMARKS = (POINTS - 1) * STRIDE + 1

    /** Below this the eyes are on top of each other and nothing can be measured. */
    private const val MIN_SPAN = 1e-4f

    /** Nose offset from the eye midpoint, in eye spans: the same yaw the session already gates on. */
    private const val NOSE_TIP = FaceGeometry.NOSE_TIP

    /**
     * [xy] is the packed mesh output as FaceGeometry expects it, [aspect] is frame height over
     * frame width. Normalised landmark y is divided by height and x by width, so on a 4:3 frame the
     * vertical axis is stretched; leaving that out of the print would let the same face measure
     * differently on a phone and on a webcam rather than merely on a different head.
     */
    fun of(xy: FloatArray, aspect: Float): MeshSignature? {
        if (xy.size < MIN_LANDMARKS * 2) return null
        if (!aspect.isFinite() || aspect <= 0f) return null
        val right = eyeCentre(xy, FaceGeometry.RIGHT_EYE, aspect)
        val left = eyeCentre(xy, FaceGeometry.LEFT_EYE, aspect)
        val span = distance(right, left)
        if (span < MIN_SPAN) return null
        val origin = floatArrayOf((right[0] + left[0]) / 2, (right[1] + left[1]) / 2)
        // Levelling the eye line first is what makes a tilted head the same face.
        val angle = atan2((left[1] - right[1]).toDouble(), (left[0] - right[0]).toDouble()).toFloat()
        val cosine = cos(angle.toDouble()).toFloat()
        val sine = sin(angle.toDouble()).toFloat()
        val raw = ArrayList<Float>(DIMENSIONS)
        var edgeLeft = Float.MAX_VALUE
        var edgeTop = Float.MAX_VALUE
        var edgeRight = -Float.MAX_VALUE
        var edgeBottom = -Float.MAX_VALUE
        for (index in 0 until POINTS) {
            val point = position(xy, index * STRIDE, aspect)
            edgeLeft = minOf(edgeLeft, xy[index * STRIDE * 2])
            edgeRight = maxOf(edgeRight, xy[index * STRIDE * 2])
            edgeTop = minOf(edgeTop, xy[index * STRIDE * 2 + 1])
            edgeBottom = maxOf(edgeBottom, xy[index * STRIDE * 2 + 1])
            val dx = point[0] - origin[0]
            val dy = point[1] - origin[1]
            raw.add((dx * cosine + dy * sine) / span)
            raw.add((dy * cosine - dx * sine) / span)
        }
        if (raw.any { !it.isFinite() }) return null
        val nose = position(xy, NOSE_TIP, aspect)
        val noseX = (nose[0] - origin[0]) * cosine + (nose[1] - origin[1]) * sine
        // One normalizer for the whole app, because the stored contract is the unit-length print.
        val vector = FaceTemplate.normalized(raw) ?: return null
        return MeshSignature(
            vector = vector,
            yaw = noseX / span,
            region = FrameRegion(edgeLeft, edgeTop, edgeRight, edgeBottom)
        )
    }

    private fun eyeCentre(xy: FloatArray, eye: IntArray, aspect: Float): FloatArray {
        val a = position(xy, eye[0], aspect)
        val b = position(xy, eye[1], aspect)
        return floatArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
    }

    private fun position(xy: FloatArray, index: Int, aspect: Float): FloatArray =
        floatArrayOf(xy[index * 2], xy[index * 2 + 1] * aspect)

    private fun distance(a: FloatArray, b: FloatArray): Float =
        hypot((a[0] - b[0]).toDouble(), (a[1] - b[1]).toDouble()).toFloat()
}
