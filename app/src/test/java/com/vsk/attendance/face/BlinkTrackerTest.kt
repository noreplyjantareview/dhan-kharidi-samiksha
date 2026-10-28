package com.vsk.attendance.face

import org.junit.Assert.*
import org.junit.Test

/**
 * The blink threshold is the one number in this feature that decides whether a field employee can
 * finish at all, and people rest their eyelids at very different widths. These cases pin the
 * per-person baseline behaviour that a fixed threshold would break.
 */
class BlinkTrackerTest {
    private val openWide = 0.30f
    private val openNarrow = 0.12f
    private val shut = 0.02f

    private fun closed(tracker: BlinkTracker, ratio: Float, nowMs: Long): Boolean =
        tracker.observe(ratio, 0f, true, nowMs).eyeClosed

    @Test fun anOpenEyeScoresNoClosureOnceTheBaselineIsLearned() {
        val tracker = BlinkTracker()
        assertEquals(0f, tracker.observe(openWide, 0f, true, 0).closure, 0.001f)
        assertTrue(tracker.isReadyToSample(tracker.observe(openWide, 0f, true, 150).closure))
    }

    @Test fun closureIsMeasuredAgainstThisPersonsOwnOpenEye() {
        val wide = BlinkTracker()
        wide.observe(openWide, 0f, true, 0)
        val narrow = BlinkTracker()
        narrow.observe(openNarrow, 0f, true, 0)
        assertTrue("A narrow eye must still register a shut eye", closed(narrow, shut, 150))
        assertTrue(closed(wide, shut, 150))
        // Half shut reads as open for the wide-eyed person, which is the point: the threshold
        // follows the person instead of the camera.
        assertFalse(closed(wide, openWide / 2, 300))
    }

    @Test fun aBlinkIsReportedOnceOnTheFrameTheEyeReopens() {
        val tracker = BlinkTracker()
        tracker.observe(openWide, 0f, true, 0)
        val first = tracker.observe(shut, 0f, true, 150)
        val second = tracker.observe(shut, 0f, true, 300)
        val reopened = tracker.observe(openWide, 0f, true, 450)
        val after = tracker.observe(openWide, 0f, true, 600)
        assertFalse(first.blink)
        assertFalse(second.blink)
        assertTrue("The reopen frame is the blink", reopened.blink)
        assertFalse(after.blink)
        assertTrue("Peak closure must clear the threshold",
            reopened.peakClosure > BlinkTracker.CLOSE_T)
        assertEquals("Peak closure is reported once", 0f, after.peakClosure, 0.001f)
    }

    @Test fun eyeHeldShutPastTheWindowIsNotABlink() {
        val tracker = BlinkTracker()
        tracker.observe(openWide, 0f, true, 0)
        tracker.observe(shut, 0f, true, 150)
        tracker.observe(shut, 0f, true, 800)
        assertFalse("A slow squeeze is not a blink",
            tracker.observe(openWide, 0f, true, 1_600).blink)
        // The tracker recovers: the next natural blink is still seen.
        tracker.observe(shut, 0f, true, 1_750)
        assertTrue(tracker.observe(openWide, 0f, true, 1_900).blink)
    }

    @Test fun aSlowPhonesSingleClosedFrameStillCountsAsABlink() {
        val tracker = BlinkTracker()
        tracker.observe(openWide, 0f, true, 0)
        assertTrue(closed(tracker, shut, 800))
        assertTrue("A phone barely managing a frame every 800ms must still be able to blink",
            tracker.observe(openWide, 0f, true, 1_600).blink)
    }

    @Test fun expressionScoresCanCloseAnEyeTheGeometryMisses() {
        val tracker = BlinkTracker()
        tracker.observe(openWide, 0f, true, 0)
        val reading = tracker.observe(openWide, 0.9f, true, 150)
        assertTrue("The model's own eyelid score is a floor under the ratio", reading.eyeClosed)
        assertEquals(0.9f, reading.closure, 0.001f)
    }

    @Test fun outOfFrameFacesDoNotTeachTheBaseline() {
        val tracker = BlinkTracker()
        repeat(5) { tracker.observe(0.5f, 0f, false, it * 150L) }
        assertEquals(0f, tracker.observe(openWide, 0f, true, 900).closure, 0.001f)
    }

    @Test fun nonFiniteReadingsAreTreatedAsClosed() {
        for (ratio in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val tracker = BlinkTracker()
            tracker.observe(openWide, 0f, true, 0)
            assertTrue("A broken reading must never look like an open eye",
                tracker.observe(ratio, Float.NaN, true, 150).eyeClosed)
        }
    }

    @Test fun anImplausibleOpenEyeCannotPoisonTheBaseline() {
        val tracker = BlinkTracker()
        tracker.observe(openWide, 0f, true, 0)
        val bad = tracker.observe(1f, 0f, true, 150)
        assertFalse("A single impossible read must not be called a shut eye", bad.eyeClosed)
        tracker.observe(openWide, 0f, true, 300)
        assertTrue("The next blink is still measured against this person's own open eye",
            closed(tracker, shut, 450))
    }

    @Test fun aChangedOpenEyeIsLearnedAgainWithinTheCheck() {
        val tracker = BlinkTracker()
        tracker.observe(0.50f, 0f, true, 0)
        var reading: BlinkReading? = null
        repeat(400) { reading = tracker.observe(0.25f, 0f, true, (it + 1) * 150L) }
        assertTrue("The decaying maximum must forget the old eye width",
            tracker.isReadyToSample(reading!!.closure))
    }

    @Test fun resetForgetsTheBaselineAndTheClosedRun() {
        val tracker = BlinkTracker()
        tracker.observe(openWide, 0f, true, 0)
        tracker.observe(shut, 0f, true, 150)
        tracker.reset()
        val reopened = tracker.observe(openWide, 0f, true, 300)
        assertFalse("A blink cannot survive a reset", reopened.blink)
        assertEquals(0f, reopened.closure, 0.001f)
    }

    @Test fun theOpenAndCloseThresholdsLeaveAHysteresisGap() {
        val tracker = BlinkTracker()
        assertTrue(BlinkTracker.OPEN_T < BlinkTracker.CLOSE_T)
        assertTrue(tracker.isReadyToSample(BlinkTracker.OPEN_T - 0.01f))
        assertFalse("Between the thresholds the eye is not yet safe to photograph",
            tracker.isReadyToSample(BlinkTracker.OPEN_T))
        assertFalse(tracker.isReadyToSample(BlinkTracker.CLOSE_T))
    }

    @Test fun closureAlwaysStaysInsideZeroToOne() {
        val tracker = BlinkTracker()
        tracker.observe(0.14f, 0f, true, 0)
        var now = 150L
        for (case in listOf(0f, 0.001f, 1f, 4f, -3f, Float.NaN)) {
            val reading = tracker.observe(case, if (case.isNaN()) 0f else 2f, true, now)
            now += 150
            assertTrue("closure=${reading.closure} for ratio=$case", reading.closure in 0f..1f)
        }
    }
}
