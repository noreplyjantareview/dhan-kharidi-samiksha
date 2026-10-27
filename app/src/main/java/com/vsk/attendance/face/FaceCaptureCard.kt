package com.vsk.attendance.face

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/**
 * Live front-camera preview with a capture button. The frame is analysed
 * in memory only — nothing is written to disk or uploaded.
 */
@Composable
fun FaceCaptureCard(
    buttonLabel: String,
    onFace: (face: Face, imgW: Int, imgH: Int, bitmap: Bitmap) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonColor: Color = Color(0xFF1D4ED8)
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val detector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.15f)
                .build()
        )
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    capture
                )
            } catch (e: Exception) {
                onError("Camera could not be started: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            detector.close()
            executor.shutdownNow()
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(20.dp))
        ) { view ->
            view.scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        Button(
            onClick = {
                val capture = imageCapture
                if (capture == null) { onError("Camera is still starting — try again."); return@Button }
                capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val media = image.image
                        if (media == null) {
                            image.close()
                            onError("Camera error — try again.")
                            return
                        }
                        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
                        val base = image.toBitmap()
                        val rot = image.imageInfo.rotationDegrees % 360
                        val upright = if (rot != 0) {
                            Bitmap.createBitmap(
                                base, 0, 0, base.width, base.height,
                                Matrix().apply { postRotate(rot.toFloat()) }, true
                            )
                        } else base
                        val w = upright.width
                        val h = upright.height
                        detector.process(input)
                            .addOnSuccessListener { faces ->
                                when {
                                    faces.isEmpty() -> onError("No face detected — hold the phone at face level.")
                                    faces.size > 1 -> onError("Keep only one face in the frame.")
                                    else -> onFace(faces[0], w, h, upright)
                                }
                            }
                            .addOnFailureListener { onError("Face detection failed — try again.") }
                            .addOnCompleteListener { image.close() }
                    }

                    override fun onError(e: ImageCaptureException) {
                        onError("Camera error: ${e.message}")
                    }
                })
            },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(buttonLabel) }
    }
}
