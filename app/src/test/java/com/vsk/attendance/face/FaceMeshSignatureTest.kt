package com.vsk.attendance.face

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A synthetic mesh, because everything this print promises is about the maths: the same face measured
 * from a different distance, height, tilt or pixel aspect has to give the same numbers, and a face
 * whose shape really changed must not. Real landmark sets can only come from a phone.
 */
class FaceMeshSignatureTest {
    private val dimensions = FaceTemplate.DIMENSIONS
    private val eyeSpan = 0.30f

    private val used = BooleanArray(FaceGeometry.LANDMARK_COUNT).also { flags ->
        listOf(FaceGeometry.RIGHT_EYE, FaceGeometry.LEFT_EYE).forEach { eye ->
            eye.forEach { index -> flags[index] = true }
        }
        intArrayOf(FaceGeometry.NOSE_TIP, FaceGeometry.MOUTH_RIGHT, FaceGeometry.MOUTH_LEFT, CHIN)
            .forEach { index -> flags[index] = true }
    }

    /**
     * [seed] picks the unnamed points, so a different seed is a different person. [shakeSeed] is the
     * frame-to-frame wobble of the mesh on one person, and [jaw] widens the lower face only, which is
     * the kind of difference a shape print is supposed to be able to see.
     */
    private fun face(
        span: Float = eyeSpan,
        noseOffset: Float = 0f,
        seed: Int = 991,
        shake: Float = 0f,
        shakeSeed: Int = 7,
        jaw: Float = 1f,
        rollDegrees: Double = 0.0,
        scale: Float = 1f,
        origin: Pair<Float, Float> = 0.5f to 0.45f
    ): List<FloatArray> {
        val points = Array(FaceGeometry.LANDMARK_COUNT) { FloatArray(2) }
        val half = span / 2
        val lid = span * 0.16f
        place(points, FaceGeometry.RIGHT_EYE, -half, 0f, span * 0.3f, lid)
        place(points, FaceGeometry.LEFT_EYE, half, 0f, span * 0.3f, -lid)
        points[FaceGeometry.NOSE_TIP][0] = noseOffset
        points[FaceGeometry.NOSE_TIP][1] = span * 0.6f
        points[FaceGeometry.MOUTH_RIGHT][0] = -span * 0.42f * jaw
        points[FaceGeometry.MOUTH_RIGHT][1] = span * 1.05f
        points[FaceGeometry.MOUTH_LEFT][0] = span * 0.42f * jaw
        points[FaceGeometry.MOUTH_LEFT][1] = span * 1.05f
        points[CHIN][0] = 0f
        points[CHIN][1] = span * 1.5f
        val random = java.util.Random(seed.toLong())
        for (index in points.indices) {
            if (used[index]) continue
            points[index][0] = (random.nextFloat() - 0.5f) * span * 2.4f
            points[index][1] = (random.nextFloat() - 0.5f) * span * 3f
        }
        if (jaw != 1f) {
            for (index in points.indices) {
                if (points[index][1] > span * 0.4f) points[index][0] *= jaw
            }
        }
        if (shake > 0f) {
            val wobble = java.util.Random(shakeSeed.toLong())
            for (index in points.indices) {
                points[index][0] += (wobble.nextFloat() - 0.5f) * 2 * shake
                points[index][1] += (wobble.nextFloat() - 0.5f) * 2 * shake
            }
        }
        val angle = Math.toRadians(rollDegrees)
        val cosine = cos(angle).toFloat()
        val sine = sin(angle).toFloat()
        return points.map { point ->
            val x = point[0] * scale
            val y = point[1] * scale
            floatArrayOf(x * cosine - y * sine + origin.first, x * sine + y * cosine + origin.second)
        }
    }

    /** Packed normalized landmarks. [aspect] is frame height over frame width. */
    private fun xy(points: List<FloatArray>, aspect: Float): FloatArray {
        val packed = FloatArray(points.size * 2)
        points.forEachIndexed { index, point ->
            packed[index * 2] = point[0]
            // Normalized y divides by pixel height, so width-units go in as width-units over aspect.
            packed[index * 2 + 1] = point[1] / aspect
        }
        return packed
    }

    private fun signature(points: List<FloatArray>, aspect: Float = 1f): MeshSignature? =
        FaceMeshSignature.of(xy(points, aspect), aspect)

    private fun print(points: List<FloatArray>, aspect: Float = 1f): List<Float> =
        signature(points, aspect)!!.vector

    private fun scoreOf(other: List<FloatArray>, aspect: Float = 1f): Float =
        FaceTemplate.cosine(print(face()), print(other, aspect))

    @Test fun dimensionsMatchTheStoredTemplate() {
        assertEquals(78, FaceMeshSignature.DIMENSIONS)
        assertEquals(FaceTemplate.DIMENSIONS, FaceMeshSignature.DIMENSIONS)
        val vector = print(face())
        assertEquals(dimensions, vector.size)
        assertEquals(1.0, sqrt(vector.sumOf { it.toDouble() * it }), 0.00001)
        assertTrue("every value stays inside the server's bounds", vector.all { it in -1.02f..1.02f })
    }

    @Test fun theSameFaceMeasuresTheSameFromAnywhereInTheFrame() {
        listOf(
            "moved right and down" to face(origin = 0.72f to 0.31f),
            "closer to the camera" to face(scale = 1.4f),
            "further from the camera" to face(scale = 0.6f),
            "rolled clockwise" to face(rollDegrees = 18.0),
            "rolled anticlockwise" to face(rollDegrees = -18.0)
        ).forEach { (name, points) ->
            val score = FaceTemplate.cosine(print(face()), print(points))
            assertTrue("$name must still be the same face, scored $score", score > FaceTemplate.MATCH_THRESHOLD)
        }
    }

    @Test fun pixelAspectDoesNotChangeThePrint() {
        val square = print(face(), aspect = 1f)
        listOf(1.333f, 0.75f, 2f).forEach { aspect ->
            val score = FaceTemplate.cosine(square, print(face(), aspect = aspect))
            assertTrue("aspect $aspect scored $score", score > FaceTemplate.MATCH_THRESHOLD)
        }
    }

    @Test fun halfPercentMeshWobbleIsStillTheSameFace() {
        // What a stable mesh actually does between frames, in eye spans.
        val reference = print(face())
        listOf(3, 23, 41, 77, 101).forEach { shakeSeed ->
            val score = FaceTemplate.cosine(reference, print(face(shake = eyeSpan * 0.005f, shakeSeed = shakeSeed)))
            assertTrue("wobble with seed $shakeSeed scored $score", score > FaceTemplate.MATCH_THRESHOLD)
        }
    }

    @Test fun shapeChangesReduceTheScoreInTheOrderTheyShould() {
        val reference = print(face())
        val slight = FaceTemplate.cosine(reference, print(face(jaw = 1.03f)))
        val wide = FaceTemplate.cosine(reference, print(face(jaw = 1.15f)))
        val stranger = FaceTemplate.cosine(reference, print(face(seed = 4242)))
        assertTrue("a wider jaw must read as less alike: $slight then $wide", slight > wide)
        assertTrue("a different face must read as the least alike: $wide then $stranger", wide > stranger)
        assertTrue("and a stranger must fail the operating point", stranger < FaceTemplate.MATCH_THRESHOLD)
    }

    @Test fun differentNosePlacementIsVisibleAsYaw() {
        assertEquals(0f, signature(face(noseOffset = 0f))!!.yaw, 0.001f)
        // The gate the session already applies is in eye spans, so a 0.03 span offset is 0.10 yaw.
        val right = signature(face(noseOffset = 0.03f))!!.yaw
        val left = signature(face(noseOffset = -0.03f))!!.yaw
        assertTrue("nose offset must read as yaw, got $right", right in 0.09f..0.11f)
        assertTrue("and change sign with it, got $left", left in -0.11f..-0.09f)
    }

    @Test fun theRegionCoversTheSampledLandmarks() {
        val region = signature(face(origin = 0.5f to 0.5f))!!.region
        assertTrue(region.left < 0.5f && region.right > 0.5f)
        assertTrue(region.top < 0.5f && region.bottom > 0.5f)
        assertTrue(region.left <= region.right && region.top <= region.bottom)
    }

    @Test fun aShortMeshOrDegenerateEyesProduceNoPrint() {
        // One landmark short of the last stride index is one the print cannot complete.
        val truncated = face().subList(0, (FaceMeshSignature.POINTS - 1) * FaceMeshSignature.STRIDE)
        assertNull(FaceMeshSignature.of(xy(truncated, 1f), 1f))
        assertNull("eyes collapsed onto each other leave no scale", FaceMeshSignature.of(xy(face(span = 1e-7f), 1f), 1f))
    }

    @Test fun nonFiniteLandmarksAndAspectAreRefused() {
        val broken = face().map { it.copyOf() }
        broken[FaceMeshSignature.STRIDE * 4][0] = Float.NaN
        assertNull(FaceMeshSignature.of(xy(broken, 1f), 1f))
        val edge = face().map { it.copyOf() }
        edge[0][1] = Float.POSITIVE_INFINITY
        assertNull(FaceMeshSignature.of(xy(edge, 1f), 1f))
        listOf(Float.NaN, 0f, -1f).forEach { aspect ->
            assertNull("aspect $aspect", FaceMeshSignature.of(xy(face(), aspect), aspect))
        }
    }

    private fun place(
        points: Array<FloatArray>,
        eye: IntArray,
        centreX: Float,
        centreY: Float,
        halfWidth: Float,
        lift: Float
    ) {
        // FaceGeometry orders each eye outer corner, inner corner, then the two lids.
        points[eye[0]][0] = centreX - halfWidth
        points[eye[0]][1] = centreY
        points[eye[1]][0] = centreX + halfWidth
        points[eye[1]][1] = centreY
        points[eye[2]][0] = centreX
        points[eye[2]][1] = centreY - lift
        points[eye[3]][0] = centreX
        points[eye[3]][1] = centreY + lift
    }

    private companion object {
        /** The mesh's chin point: never required for framing, but part of the shape. */
        const val CHIN = 152
    }
}
