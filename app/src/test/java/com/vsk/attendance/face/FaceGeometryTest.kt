package com.vsk.attendance.face

import org.junit.Assert.*
import org.junit.Test

/**
 * These indices and thresholds were validated against a real laptop webcam in the browser prototype
 * before any of them reached Android. The chin is deliberately absent from every gate: a phone held
 * at chest height always crops the jaw, and that one condition used to hold a perfectly usable face
 * out of the ready state for seconds.
 */
class FaceGeometryTest {
    private val width = 640

    private fun face(overrides: Map<Int, Pair<Float, Float>> = emptyMap()): FloatArray {
        val default = mapOf(
            33 to (0.35f to 0.40f), 133 to (0.42f to 0.40f),
            159 to (0.385f to 0.39f), 145 to (0.385f to 0.41f),
            362 to (0.58f to 0.40f), 263 to (0.65f to 0.40f),
            386 to (0.615f to 0.39f), 374 to (0.615f to 0.41f),
            4 to (0.50f to 0.52f), 61 to (0.44f to 0.66f), 291 to (0.56f to 0.66f),
            152 to (0.50f to 0.90f)
        )
        val xy = FloatArray(FaceGeometry.LANDMARK_COUNT * 2)
        for (index in default.keys + overrides.keys) {
            val point = overrides[index] ?: default.getValue(index)
            xy[index * 2] = point.first
            xy[index * 2 + 1] = point.second
        }
        return xy
    }

    /** Move the whole face: [factor] scales about the centre, [dx] and [dy] shift it. */
    private fun place(xy: FloatArray, factor: Float = 1f, dx: Float = 0f, dy: Float = 0f): FloatArray {
        for (index in xy.indices step 2) {
            xy[index] = 0.5f + (xy[index] - 0.5f) * factor + dx
            xy[index + 1] = 0.5f + (xy[index + 1] - 0.5f) * factor + dy
        }
        return xy
    }

    private fun shutRightEye() = face(mapOf(159 to (0.385f to 0.40f), 145 to (0.385f to 0.40f)))

    @Test fun aWellFramedOpenFaceHasNoComplaint() {
        val xy = face()
        assertNull(FaceGeometry.framing(xy, width))
        val openness = FaceGeometry.eyeOpenness(xy)
        assertTrue("Open eye ratio should sit in the natural 0.2-0.36 band: $openness",
            openness in 0.2f..0.36f)
        assertTrue(Math.abs(FaceGeometry.rollDegrees(xy)) < 1.0)
    }

    @Test fun aCroppedChinStillCountsAsUsable() {
        val xy = face(mapOf(152 to (0.50f to 1.60f)))
        assertTrue("The chin is outside the frame", FaceGeometry.y(xy, 152) > 1f)
        assertNull(FaceGeometry.framing(xy, width))
    }

    @Test fun shutEyesReportAlmostNoOpenRatio() {
        val xy = face(mapOf(
            159 to (0.385f to 0.40f), 145 to (0.385f to 0.40f),
            386 to (0.615f to 0.40f), 374 to (0.615f to 0.40f)
        ))
        assertTrue(FaceGeometry.eyeOpenness(xy) < 0.01f)
    }

    @Test fun oneShutEyeDecidesBecauseItIsTheMoreClosedOne() {
        val xy = shutRightEye()
        assertTrue("One twitching lid must not hide a blink",
            FaceGeometry.eyeOpenness(xy) < 0.01f)
        assertTrue(FaceGeometry.openRatio(xy, FaceGeometry.LEFT_EYE) > 0.2f)
    }

    @Test fun aFaceTooSmallIsToldToMoveCloser() {
        assertEquals(FramingReason.TooFar, FaceGeometry.framing(place(face(), factor = 0.2f), width))
        assertEquals(FaceCopy.MOVE_CLOSER, FramingReason.TooFar.instruction)
    }

    @Test fun aFaceTooLargeIsToldToMoveBack() {
        assertEquals(FramingReason.TooClose, FaceGeometry.framing(place(face(), factor = 2f), width))
    }

    @Test fun anEyeOffTheEdgeIsToldToKeepTheWholeFaceInFrame() {
        assertEquals(FramingReason.EyesOut, FaceGeometry.framing(place(face(), dx = 0.35f), width))
        assertEquals(FramingReason.EyesOut, FaceGeometry.framing(place(face(), dy = -0.35f), width))
    }

    @Test fun aCroppedNoseIsToldToKeepTheWholeFaceInFrame() {
        assertEquals(FramingReason.NoseOut,
            FaceGeometry.framing(face(mapOf(4 to (0.50f to 0.99f))), width))
    }

    @Test fun aCroppedMouthIsToldToKeepTheWholeFaceInFrame() {
        assertEquals(FramingReason.MouthOut,
            FaceGeometry.framing(face(mapOf(291 to (0.56f to 0.99f))), width))
    }

    @Test fun sizeIsCheckedBeforePosition() {
        // A face that is both far away and off-centre must be told the one thing that fixes both.
        assertEquals(FramingReason.TooFar,
            FaceGeometry.framing(place(face(), factor = 0.2f, dx = 0.4f), width))
    }

    @Test fun aTiltedHeadIsToldToHoldItStraight() {
        assertEquals(FramingReason.Tilt,
            FaceGeometry.framing(face(mapOf(263 to (0.65f to 0.573f))), width))
        assertNull("A small tilt is normal posture, not a complaint",
            FaceGeometry.framing(face(mapOf(263 to (0.65f to 0.509f))), width))
    }

    @Test fun eyeSpanTracksFaceSizeInPixels() {
        assertEquals(192f, FaceGeometry.eyeSpanPx(face(), width), 1f)
        assertEquals(96f, FaceGeometry.eyeSpanPx(face(), width / 2), 1f)
    }

    @Test fun meshIndicesMatchThePublishedFaceModel() {
        assertEquals(FaceGeometry.LANDMARK_COUNT, 478)
        assertTrue(FaceGeometry.RIGHT_EYE.contentEquals(intArrayOf(33, 133, 159, 145)))
        assertTrue(FaceGeometry.LEFT_EYE.contentEquals(intArrayOf(362, 263, 386, 374)))
        assertEquals(33, FaceGeometry.OUTER_RIGHT)
        assertEquals(263, FaceGeometry.OUTER_LEFT)
        assertEquals(4, FaceGeometry.NOSE_TIP)
        assertTrue(FaceGeometry.MOUTH_RIGHT == 61 && FaceGeometry.MOUTH_LEFT == 291)
    }

    @Test fun everyFramingReasonSpeaksBothLanguages() {
        for (reason in FramingReason.entries) {
            assertTrue(reason.instruction.english.isNotBlank())
            assertTrue(reason.instruction.hindi.isNotBlank())
            assertTrue("One short line: ${reason.instruction.english}",
                reason.instruction.english.length <= 52)
        }
    }
}
