package com.vsk.attendance.face

import kotlin.math.max

/** One frame's verdict from the eyes. [blink] is true only on the frame the eye reopens. */
internal data class BlinkReading(
    val closure: Float,
    val baseline: Float,
    val eyeClosed: Boolean,
    val blink: Boolean,
    val peakClosure: Float
)

/**
 * Blink detector driven by eyelid geometry, with the expression score only as a floor.
 *
 * People hold different eye widths at rest, so closure is measured against each person's own
 * open-eye baseline instead of a fixed ratio. The baseline is a slowly decaying maximum, which is
 * what stops a blink from dragging its own reference down and hiding the next one.
 *
 * Math and thresholds were validated against a real webcam in the browser prototype
 * (peak 0.90 against a 0.55 threshold, 133-217ms natural blinks, 0 framing rejections).
 */
internal class BlinkTracker {
    private var baseline = 0f
    private var closedRun = 0
    private var closeStartedAt = 0L
    private var peak = 0f

    fun reset() {
        baseline = 0f
        closedRun = 0
        closeStartedAt = 0L
        peak = 0f
    }

    /** True when the eye is open enough to photograph a usable face. */
    fun isReadyToSample(closure: Float): Boolean = closure < OPEN_T

    fun observe(openRatio: Float, expressionClosure: Float, framed: Boolean, nowMs: Long): BlinkReading {
        val ratio = if (openRatio.isFinite()) openRatio.coerceIn(0f, 1f) else 0f
        // Only a plausible open eye teaches the baseline. A single bad read of a full-height eye
        // would otherwise make every later blink look like a shut eye and stall the check.
        if (framed && ratio in LEARN_MIN..LEARN_MAX) baseline = max(ratio, baseline * BASELINE_DECAY)
        val reference = max(baseline, MIN_BASELINE)
        val relative = (1f - ratio / reference).coerceIn(0f, 1f)
        val floor = if (expressionClosure.isFinite()) expressionClosure.coerceIn(0f, 1f) else 0f
        val closure = max(relative, floor)
        val closed = closure >= CLOSE_T
        if (closed) {
            if (closedRun == 0) {
                closeStartedAt = nowMs
                peak = closure
            }
            closedRun++
            peak = max(peak, closure)
            return BlinkReading(closure, reference, eyeClosed = true, blink = false, peakClosure = peak)
        }
        val blinked = closedRun > 0 && nowMs - closeStartedAt <= BLINK_MAX_MS
        val blinkedPeak = peak
        closedRun = 0
        peak = 0f
        return BlinkReading(closure, reference, eyeClosed = false, blink = blinked, peakClosure = blinkedPeak)
    }

    companion object {
        /** Fraction of the person's own open eye that counts as shut. */
        const val CLOSE_T = 0.55f

        /** Below this the eye is open again, and only then is a face worth photographing. */
        const val OPEN_T = 0.30f

        private const val MIN_BASELINE = 0.14f
        private const val BASELINE_DECAY = 0.999f

        /** Eyelid ratios a real open eye can show; anything else is a bad mesh read. */
        private const val LEARN_MIN = 0.05f
        private const val LEARN_MAX = 0.60f

        /**
         * Longer than this is a squeeze or a resting eyelid, not a blink. The browser prototype
         * proved natural blinks run 133-217ms, but a phone under 8 frames a second cannot sample
         * that fast, so the window has to stay wide enough for a blink to be seen at all.
         */
        private const val BLINK_MAX_MS = 1_200L
    }
}
