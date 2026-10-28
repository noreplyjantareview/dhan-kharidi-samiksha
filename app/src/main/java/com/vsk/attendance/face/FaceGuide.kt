package com.vsk.attendance.face

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max

/**
 * MediaPipe FaceLandmarker wrapper: the one model the check now runs.
 *
 * It supplies the framing and blink guidance on every frame and the mesh shape print that carries
 * identity, so recognition no longer needs a second native engine. The CPU delegate is deliberate -
 * the GPU one fails shader setup on some cheap devices, and the whole model runs in well under the
 * 300ms frame budget.
 */
internal class FaceGuide private constructor(private val landmarker: FaceLandmarker) {

    /** One frame read by the mesh: what to tell the employee, and what this face measures. */
    fun read(bitmap: Bitmap, timestampMs: Long): FaceReading? {
        val result = detect(bitmap, timestampMs)
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return null
        val landmarks = faces[0]
        if (landmarks.size < MIN_LANDMARKS) return null
        val xy = FloatArray(landmarks.size * 2)
        for (i in landmarks.indices) {
            xy[i * 2] = landmarks[i].x()
            xy[i * 2 + 1] = landmarks[i].y()
        }
        val signature = FaceMeshSignature.of(xy, bitmap.height.toFloat() / bitmap.width) ?: return null
        return FaceReading(
            guidance = FaceGuidance(
                openRatio = FaceGeometry.eyeOpenness(xy),
                expressionClosure = blinkFloor(result),
                framing = FaceGeometry.framing(xy, bitmap.width)
            ),
            signature = signature
        )
    }

    /**
     * The first inference builds the native graph and takes far longer than the rest, which used to
     * look like a frozen camera. Burning it on a blank frame while the screen still says "ready"
     * keeps that cost outside the employee's face check. A failure here is reported rather than
     * ignored: every guidance frame calls the same graph, so a broken warm-up means broken checks.
     */
    fun warmUp() {
        val blank = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            detect(blank, SystemClock.elapsedRealtime())
        } finally {
            blank.recycle()
        }
    }

    private var lastTimestampMs = -1L

    /** Video mode rejects a timestamp that is not strictly ahead of the previous one. */
    private fun detect(bitmap: Bitmap, timestampMs: Long): FaceLandmarkerResult {
        val stamp = max(timestampMs, lastTimestampMs + 1)
        lastTimestampMs = stamp
        val image = BitmapImageBuilder(bitmap).build()
        try {
            return landmarker.detectForVideo(image, stamp)
        } finally {
            // The wrapper holds native memory; guidance runs many times a second.
            image.close()
        }
    }

    /**
     * The mesh model's own eyelid scores, used only as a floor under the geometric ratio. Names are
     * matched case-insensitively so a model rename cannot silently pin the blink score at zero.
     */
    private fun blinkFloor(result: FaceLandmarkerResult): Float {
        val shapes = result.faceBlendshapes().orElse(null)
        if (shapes.isNullOrEmpty()) return 0f
        var worst = 0f
        for (category in shapes[0]) {
            val name = category.categoryName()
            if (name.contains("blink", ignoreCase = true) && name.contains("eye", ignoreCase = true)) {
                worst = maxOf(worst, category.score())
            }
        }
        return worst
    }

    companion object {
        private const val MIN_LANDMARKS = 468

        @Volatile private var instance: FaceGuide? = null

        fun get(context: Context): FaceGuide = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): FaceGuide {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath("face/face_landmarker.task")
                        .setDelegate(Delegate.CPU)
                        .build()
                )
                .setRunningMode(RunningMode.VIDEO)
                .setOutputFaceBlendshapes(true)
                .setNumFaces(1)
                .build()
            return FaceGuide(FaceLandmarker.createFromOptions(context, options))
        }
    }
}
