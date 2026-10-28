package com.vsk.attendance.face

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

internal data class FaceObservation(
    val issue: FaceInstruction? = null,
    val vector: List<Float> = emptyList(),
    val yaw: Float = 0f,
    val interruption: FaceInterruption = FaceInterruption.Quality
)

internal class FaceEngine private constructor(context: Context) {
    private val detector: FaceDetectorYN
    private val recognizer: FaceRecognizerSF
    private val antiSpoof: org.opencv.dnn.Net

    init {
        check(OpenCVLoader.initLocal()) { "Face engine could not load on this phone." }
        Core.setNumThreads(2)
        detector = FaceDetectorYN.create(
            modelPath(context, "yunet.onnx", "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"),
            "", Size(640.0, 480.0), 0.65f, 0.3f, 5000
        )
        recognizer = FaceRecognizerSF.create(
            modelPath(context, "sface.onnx", "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"), ""
        )
        antiSpoof = Dnn.readNetFromONNX(
            modelPath(context, "minifasnet.onnx", "d7b3cd9ba8a7ceb13baa8c4720902e27ca3112eff52f926c08804af6b6eecc7b")
        )
        check(!antiSpoof.empty()) { "Anti-spoof model could not load." }
        antiSpoof.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
        antiSpoof.setPreferableTarget(Dnn.DNN_TARGET_CPU)
    }

    @Synchronized
    fun analyze(bitmap: Bitmap): FaceObservation {
        val rgba = Mat()
        val bgr = Mat()
        val faces = Mat()
        val aligned = Mat()
        val feature = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            detector.setInputSize(bgr.size())
            detector.detect(bgr, faces)
            if (faces.rows() == 0) return FaceObservation(
                FaceCopy.FACE_LOST, interruption = FaceInterruption.FaceLost
            )
            if (faces.rows() > 1) return FaceObservation(
                FaceCopy.TWO_FACES, interruption = FaceInterruption.Identity
            )
            val points = FloatArray(15)
            faces.get(0, 0, points)
            val x = points[0]
            val y = points[1]
            val width = points[2]
            val height = points[3]
            // Floors track FaceGeometry's mesh-based guidance so a frame the employee was told to be
            // ready for is never then thrown away by a stricter second opinion.
            if (width < 85 || height < 100 || width < bitmap.width * 0.15f) {
                return FaceObservation(FaceCopy.MOVE_CLOSER)
            }
            if (width > bitmap.width * 0.78f || height > bitmap.height * 0.88f) {
                return FaceObservation(FaceCopy.MOVE_BACK)
            }
            if (x < 4 || y < 4 || x + width > bitmap.width - 4 || y + height > bitmap.height - 4) {
                return FaceObservation(FaceCopy.FACE_IN_FRAME)
            }
            val dx = points[6] - points[4]
            val dy = points[7] - points[5]
            val eyeDistance = hypot(dx, dy)
            if (eyeDistance < 24) return FaceObservation(FaceCopy.MOVE_CLOSER)
            val roll = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            if (abs(roll) > FaceGeometry.ROLL_MAX_DEGREES) return FaceObservation(FaceCopy.UPRIGHT)
            val nx = points[8] - (points[4] + points[6]) / 2
            val ny = points[9] - (points[5] + points[7]) / 2
            val yaw = (nx * dx + ny * dy) / (eyeDistance * eyeDistance)
            val pitch = (ny * dx - nx * dy) / (eyeDistance * eyeDistance)
            if (abs(yaw) > 0.6f || pitch !in 0.02f..1.10f) {
                return FaceObservation(FaceCopy.EYE_LEVEL)
            }
            val row = faces.row(0)
            try { recognizer.alignCrop(bgr, row, aligned) } finally { row.release() }
            qualityIssue(aligned)?.let { return FaceObservation(it) }
            if (liveProbability(bgr, x, y, width, height) < 0.8f) {
                return FaceObservation(
                    FaceCopy.LIVE_FACE,
                    interruption = FaceInterruption.Pad
                )
            }
            recognizer.feature(aligned, feature)
            check(feature.total() == FaceTemplate.DIMENSIONS.toLong()) { "Face model output is incompatible." }
            val raw = FloatArray(FaceTemplate.DIMENSIONS)
            feature.get(0, 0, raw)
            val vector = FaceTemplate.normalized(raw.toList())
                ?: return FaceObservation(FaceCopy.UNREADABLE)
            return FaceObservation(vector = vector, yaw = yaw)
        } finally {
            feature.release()
            aligned.release()
            faces.release()
            bgr.release()
            rgba.release()
        }
    }

    private fun qualityIssue(aligned: Mat): FaceInstruction? {
        val gray = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        try {
            Imgproc.cvtColor(aligned, gray, Imgproc.COLOR_BGR2GRAY)
            Core.meanStdDev(gray, mean, deviation)
            val brightness = mean.toArray()[0]
            if (brightness < 38) return FaceCopy.TOO_DARK
            if (brightness > 228) return FaceCopy.TOO_BRIGHT
            if (deviation.toArray()[0] < 11) return FaceCopy.FLAT_LIGHT
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, deviation)
            val sharpness = deviation.toArray()[0]
            if (sharpness * sharpness < 18) return FaceCopy.TOO_BLURRY
            return null
        } finally {
            deviation.release()
            mean.release()
            laplacian.release()
            gray.release()
        }
    }

    private fun liveProbability(image: Mat, x: Float, y: Float, w: Float, h: Float): Float {
        val scale = min(2.7, min((image.cols() - 1) / w.toDouble(), (image.rows() - 1) / h.toDouble()))
        val cropW = w * scale
        val cropH = h * scale
        val left = (x + w / 2 - cropW / 2).coerceIn(0.0, (image.cols() - 1.0 - cropW).coerceAtLeast(0.0))
        val top = (y + h / 2 - cropH / 2).coerceIn(0.0, (image.rows() - 1.0 - cropH).coerceAtLeast(0.0))
        val right = (left + cropW).toInt()
        val bottom = (top + cropH).toInt()
        val crop = image.submat(Rect(left.toInt(), top.toInt(), right - left.toInt() + 1, bottom - top.toInt() + 1))
        val blob = Dnn.blobFromImage(crop, 1.0, Size(80.0, 80.0), Scalar(0.0), false, false)
        var output: Mat? = null
        try {
            antiSpoof.setInput(blob)
            output = antiSpoof.forward()
            check(output.total() == 3L) { "Anti-spoof output is incompatible." }
            val logits = FloatArray(3)
            output.get(intArrayOf(0, 0), logits)
            if (logits.any { !it.isFinite() }) return 0f
            val largest = logits.max()
            val probabilities = logits.map { exp((it - largest).toDouble()) }
            return (probabilities[1] / probabilities.sum()).toFloat()
        } finally {
            output?.release()
            blob.release()
            crop.release()
        }
    }

    companion object {
        @Volatile private var instance: FaceEngine? = null

        fun get(context: Context): FaceEngine = instance ?: synchronized(this) {
            instance ?: FaceEngine(context.applicationContext).also { instance = it }
        }

        private fun modelPath(context: Context, name: String, expected: String): String {
            val directory = File(context.noBackupFilesDir, "face-models")
            check(directory.isDirectory || directory.mkdirs()) { "Model storage is unavailable." }
            val file = File(directory, name)
            if (!file.exists() || hash(file) != expected) {
                context.assets.open("face/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            check(hash(file) == expected) { "Face model integrity check failed. Reinstall the official app." }
            return file.absolutePath
        }

        private fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(16384)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
