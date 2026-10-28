package com.vsk.attendance.face

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Light and sharpness read straight off the frame, because a face the mesh can still find is not
 * automatically a face worth enrolling.
 *
 * This replaces the OpenCV brightness and Laplacian checks. It samples the face region on a grid of
 * at most [MAX_SAMPLES] a side but takes Laplacian neighbours one pixel out, so a blurred frame
 * still measures flat while the cost stays a few thousand array reads on the evidence frame only.
 */
internal data class FrameStats(val brightness: Float, val contrast: Float, val edges: Float) {
    /** Only ever shown in a debug build, where the numbers are how a threshold gets calibrated. */
    override fun toString(): String = "light=%.0f contrast=%.0f edges=%.1f".format(brightness, contrast, edges)
}

internal object FaceFrameQuality {
    const val MAX_SAMPLES = 64

    // The light floors carry over from the OpenCV pass unchanged; only the edge score is a new
    // measurement on a new scale, so it is the one a field test has to confirm.
    const val MIN_BRIGHTNESS = 38f
    const val MAX_BRIGHTNESS = 228f
    const val MIN_CONTRAST = 11f
    const val MIN_EDGES = 5f

    /** Null when the region is empty or entirely outside the buffer. */
    fun measure(pixels: IntArray, width: Int, height: Int, stride: Int = width): FrameStats? {
        if (width < 3 || height < 3 || stride < width) return null
        if (pixels.size < (height - 1) * stride + width) return null
        val step = maxOf(1, maxOf(width, height) / MAX_SAMPLES)
        var count = 0
        var sum = 0.0
        var squares = 0.0
        var edgeSum = 0.0
        var edgeCount = 0
        var y = step
        while (y < height - step) {
            var x = step
            while (x < width - step) {
                val here = luma(pixels[y * stride + x])
                count++
                sum += here
                squares += here * here
                val laplacian = 4 * here - luma(pixels[(y - 1) * stride + x]) -
                    luma(pixels[(y + 1) * stride + x]) - luma(pixels[y * stride + x - 1]) -
                    luma(pixels[y * stride + x + 1])
                edgeSum += abs(laplacian)
                edgeCount++
                x += step
            }
            y += step
        }
        if (count < MIN_SAMPLES || edgeCount < MIN_SAMPLES) return null
        val mean = sum / count
        val variance = (squares / count - mean * mean).coerceAtLeast(0.0)
        return FrameStats(
            brightness = mean.toFloat(),
            contrast = kotlin.math.sqrt(variance).toFloat(),
            edges = (edgeSum / edgeCount).toFloat()
        )
    }

    fun issue(stats: FrameStats?): FaceInstruction? {
        // A region too small or too flat to measure is not a reason to blame the employee's light.
        if (stats == null) return null
        if (stats.brightness < MIN_BRIGHTNESS) return FaceCopy.TOO_DARK
        if (stats.brightness > MAX_BRIGHTNESS) return FaceCopy.TOO_BRIGHT
        if (stats.contrast < MIN_CONTRAST) return FaceCopy.FLAT_LIGHT
        if (stats.edges < MIN_EDGES) return FaceCopy.TOO_BLURRY
        return null
    }

    private const val MIN_SAMPLES = 64

    private fun luma(argb: Int): Double {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return (red * 299 + green * 587 + blue * 114 + 500) / 1000.0
    }
}
