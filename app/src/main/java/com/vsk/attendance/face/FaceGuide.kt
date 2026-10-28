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

/**
 * MediaPipe FaceLandmarker wrapper used only for guidance and blink detection.
 *
 * It never produces an identity: recognition stays with SFace and presentation screening stays with
 * MiniFASNet. The CPU delegate is deliberate - the GPU one fails shader setup on some cheap
 * devices, and the whole model runs in well under the 300ms frame budget.
 */
internal class FaceGuide private constructor(private val landmarker: FaceLandmarker) {

    /** Null when the mesh model sees no face in this frame. */
    fun read(bitmap: Bitmap, timestampMs: Long): FaceGuidance? {
        val image = BitmapImageBuilder(bitmap).build()
        val result = try {
            landmarker.detectForVideo(image, timestampMs)
        } finally {
            // The wrapper holds native memory; guidance runs many times a second.
            image.close()
        }
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return null
        val landmarks = faces[0]
        if (landmarks.size < MIN_LANDMARKS) return null
        val xy = FloatArray(landmarks.size * 2)
        for (i in landmarks.indices) {
            xy[i * 2] = landmarks[i].x()
            xy[i * 2 + 1] = landmarks[i].y()
        }
        return FaceGuidance(
            openRatio = FaceGeometry.eyeOpenness(xy),
            expressionClosure = blinkFloor(result),
            framing = FaceGeometry.framing(xy, bitmap.width)
        )
    }

    /**
     * The first inference builds the native graph and takes far longer than the rest, which used to
     * look like a frozen camera. Burning it on a blank frame while the screen still says "ready"
     * keeps that cost outside the employee's face check.
     */
    fun warmUp() {
        val blank = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val image = BitmapImageBuilder(blank).build()
        try {
            landmarker.detectForVideo(image, SystemClock.elapsedRealtime())
        } catch (_: RuntimeException) {
            // A warm-up failure is harmless: the first real frame reports whatever is wrong.
        } finally {
            image.close()
            blank.recycle()
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
