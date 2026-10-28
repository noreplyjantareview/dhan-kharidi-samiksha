package com.vsk.attendance.face

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

/**
 * The light and sharpness gate that replaced the OpenCV pass, tested on synthetic frames: the four
 * complaints an employee can actually act on have to fire on the frames that deserve them and stay
 * quiet on everything else.
 */
class FaceFrameQualityTest {
    private fun gray(value: Int) = (0xFF.toInt() shl 24) or (value and 0xFF shl 16) or
        (value and 0xFF shl 8) or (value and 0xFF)

    private fun buffer(width: Int, height: Int, at: (x: Int, y: Int) -> Int): IntArray =
        IntArray(width * height).also { pixels ->
            for (y in 0 until height) for (x in 0 until width) {
                pixels[y * width + x] = gray(at(x, y))
            }
        }

    private fun measure(width: Int, height: Int, at: (x: Int, y: Int) -> Int): FrameStats? =
        FaceFrameQuality.measure(buffer(width, height, at), width, height)

    @Test fun aSharpFrameInEvenLightIsAccepted() {
        // A two-pixel checkerboard is the sharpest thing a camera can be asked to resolve.
        val stats = measure(96, 96) { x, y -> if ((x + y) % 2 == 0) 20 else 235 }!!
        assertNull(FaceFrameQuality.issue(stats))
        assertTrue("light must read mid-range, was ${stats.brightness}", stats.brightness in 100f..160f)
        assertTrue(stats.contrast > FaceFrameQuality.MIN_CONTRAST)
        assertTrue(stats.edges > FaceFrameQuality.MIN_EDGES)
    }

    @Test fun aDimFrameIsBlamedOnTheLightNotTheEmployee() {
        val stats = measure(96, 96) { _, _ -> 12 }!!
        assertTrue(stats.brightness < FaceFrameQuality.MIN_BRIGHTNESS)
        assertEquals(FaceCopy.TOO_DARK, FaceFrameQuality.issue(stats))
        assertEquals(FaceCopy.TOO_DARK, FaceFrameQuality.issue(measure(96, 96) { x, _ -> 10 + x % 3 }))
    }

    @Test fun aBlownOutFrameAsksForShade() {
        val stats = measure(96, 96) { _, _ -> 252 }!!
        assertTrue(stats.brightness > FaceFrameQuality.MAX_BRIGHTNESS)
        assertEquals(FaceCopy.TOO_BRIGHT, FaceFrameQuality.issue(stats))
    }

    @Test fun aFlatLitFrameHasNoShapeToMeasure() {
        // Even brightness, no contrast at all: a wall of one tone.
        val stats = measure(96, 96) { _, _ -> 120 }!!
        assertTrue(stats.brightness in FaceFrameQuality.MIN_BRIGHTNESS..FaceFrameQuality.MAX_BRIGHTNESS)
        assertEquals(0f, stats.contrast, 0.0001f)
        assertEquals(FaceCopy.FLAT_LIGHT, FaceFrameQuality.issue(stats))
    }

    @Test fun aSmoothGradientIsReportedAsBlur() {
        // A ramp carries plenty of contrast but no edges, which is what a defocused face looks like.
        val stats = measure(96, 96) { x, _ -> 40 + x }!!
        assertTrue(stats.contrast > FaceFrameQuality.MIN_CONTRAST)
        assertTrue("a ramp must measure flat, edges were ${stats.edges}", stats.edges < FaceFrameQuality.MIN_EDGES)
        assertEquals(FaceCopy.TOO_BLURRY, FaceFrameQuality.issue(stats))
    }

    @Test fun softTexturePassesWhereARampDoesNot() {
        // A gentle waveform is detail; a straight ramp is a defocused edge.
        val soft = measure(96, 96) { x, _ -> (120 + 60 * sin(x * 2 * Math.PI / 12.0)).toInt() }!!
        assertNull(FaceFrameQuality.issue(soft))
        val fine = measure(96, 96) { x, _ -> (120 + 60 * sin(x * 2 * Math.PI / 3.0)).toInt() }!!
        assertTrue("coarser detail must score lower: ${soft.edges} then ${fine.edges}", soft.edges < fine.edges)
    }

    @Test fun aRegionTooSmallToJudgeReturnsNothing() {
        assertNull(measure(2, 96) { _, _ -> 128 })
        assertNull(measure(96, 2) { _, _ -> 128 })
        // Big enough to read but with too few samples inside to say anything about.
        assertNull(measure(8, 8) { _, _ -> 128 })
        assertNull(FaceFrameQuality.measure(IntArray(10), 4, 4))
        assertNull(FaceFrameQuality.measure(IntArray(96 * 96 - 1), 96, 96))
    }

    @Test fun noMeasurementIsNeverAnInstruction() {
        assertNull(FaceFrameQuality.issue(null))
    }

    @Test fun aPaddedRowStrideReadsTheSamePicture() {
        // The bitmap API writes rows at the stride, never tighter than four pixels, so reads follow it:
        // a padded buffer has to measure exactly the same face as a packed one.
        val width = 5
        val height = 96
        val stride = (width + 3) and 3.inv()
        fun noise(x: Int, y: Int) = if (((x * 73856093 + y * 19349663) shr 6 and 1) == 0) 20 else 235
        val padded = IntArray(height * stride).also { pixels ->
            for (y in 0 until height) for (x in 0 until width) pixels[y * stride + x] = gray(noise(x, y))
        }
        val packed = buffer(width, height, ::noise)
        val packedStats = FaceFrameQuality.measure(packed, width, height)
        assertNotNull("the region is readable at all", packedStats)
        assertEquals(packedStats, FaceFrameQuality.measure(padded, width, height, stride))
        // A buffer one pixel short of the last row, or a stride narrower than the region, is refused
        // rather than quietly reading the wrong pixels.
        assertNull(FaceFrameQuality.measure(IntArray((height - 1) * stride + width - 1), width, height, stride))
        assertNull(FaceFrameQuality.measure(padded, width, height, width - 1))
    }

    @Test fun theReportedNumbersAreTheOnesTheThresholdsUse() {
        val stats = FrameStats(brightness = 150f, contrast = 40f, edges = 6.5f)
        val line = stats.toString()
        listOf("light=150", "contrast=40", "edges=6.5").forEach {
            assertTrue("missing $line", line.contains(it))
        }
    }

    @Test fun samplingCostIsBoundedByTheGridNotTheFrame() {
        // A hash pattern is unpredictable at any stride, so it is the honest way to compare grid sizes.
        fun noise(x: Int, y: Int) = if (((x * 73856093 + y * 19349663) shr 6 and 1) == 0) 20 else 235
        val small = measure(64, 64, ::noise)!!
        val large = measure(640, 480, ::noise)!!
        // A bigger frame is sampled more coarsely, so what a threshold sees must not drift with it.
        assertEquals(small.brightness, large.brightness, 1f)
        assertEquals(small.contrast, large.contrast, 1f)
        assertTrue(small.edges > FaceFrameQuality.MIN_EDGES)
        assertTrue(large.edges > FaceFrameQuality.MIN_EDGES)
    }
}
