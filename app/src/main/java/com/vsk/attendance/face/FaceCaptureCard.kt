package com.vsk.attendance.face

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun FaceCaptureCard(
    enrolling: Boolean,
    template: String? = null,
    turn: String = "left",
    enabled: Boolean = true,
    buttonColor: Color = Color(0xFF1D4ED8),
    onComplete: (String?) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val completion by rememberUpdatedState(onComplete)
    val error by rememberUpdatedState(onError)
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    var message by remember { mutableStateOf("Loading secure face check…") }
    var ready by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<FaceAnalyzer?>(null) }

    SideEffect { controller?.enabled?.set(enabled) }
    DisposableEffect(context, lifecycleOwner, enrolling, template, turn) {
        ready = false
        running = false
        val main = ContextCompat.getMainExecutor(context)
        val analyzer = FaceAnalyzer(
            main = main,
            enrolling = enrolling,
            template = template,
            turn = turn,
            onProgress = { message = it },
            onRunning = { running = it },
            onComplete = { completion(it) },
            onError = { error(it) }
        )
        analyzer.enabled.set(enabled)
        controller = analyzer
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var provider: ProcessCameraProvider? = null
        analyzer.executor.execute {
            try {
                analyzer.engine = FaceEngine.get(context)
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    if (!analyzer.closed.get()) {
                        try {
                            provider = future.get()
                            analysis.setAnalyzer(analyzer.executor, analyzer)
                            provider!!.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                            ready = true
                            message = "Use even lighting, remove face coverings and hold the phone at eye level"
                        } catch (_: Exception) {
                            message = "Front camera could not start. Close this screen and retry."
                            error(message)
                        }
                    }
                }, main)
            } catch (_: Exception) {
                analyzer.post {
                    message = "Face models could not load. Reinstall the latest official app and retry."
                    error(message)
                }
            } catch (_: LinkageError) {
                analyzer.post {
                    message = "The face engine is unavailable on this phone. Contact your administrator."
                    error(message)
                }
            }
        }
        onDispose {
            analyzer.closed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            analyzer.executor.shutdown()
            controller = null
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(20.dp))
        )
        Text(message)
        if (!ready || running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { controller?.start() },
            enabled = enabled && ready && !running,
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (running) "Checking face…" else if (enrolling) "Start face registration" else "Start face check")
        }
        Text("Images are processed on this phone only. No face photos are saved or uploaded.")
    }
}

private class FaceAnalyzer(
    private val main: Executor,
    private val enrolling: Boolean,
    private val template: String?,
    private val turn: String,
    private val onProgress: (String) -> Unit,
    private val onRunning: (Boolean) -> Unit,
    private val onComplete: (String?) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {
    val executor = Executors.newSingleThreadExecutor()
    val closed = AtomicBoolean(false)
    val enabled = AtomicBoolean(true)
    private val running = AtomicBoolean(false)
    var engine: FaceEngine? = null
    private var session: FaceCheckSession? = null
    private var deadline = 0L
    private var lastFrameAt = 0L

    fun start() {
        if (closed.get() || !enabled.get() || !running.compareAndSet(false, true)) return
        onRunning(true)
        executor.execute {
            try {
                val now = SystemClock.elapsedRealtime()
                session = FaceCheckSession(enrolling, template, turn, now)
                deadline = now + 65000
                post { onProgress("Look straight at the camera and hold still") }
            } catch (e: IllegalArgumentException) {
                fail(e.message ?: "Face registration is not current. Ask your administrator for a reset.")
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        var base: Bitmap? = null
        var upright: Bitmap? = null
        try {
            val now = SystemClock.elapsedRealtime()
            if (closed.get() || !enabled.get() || !running.get() || now - lastFrameAt < 300) return
            val current = session ?: return
            if (now > deadline) {
                fail("Face check timed out. Close and try again in even lighting.")
                return
            }
            lastFrameAt = now
            base = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            upright = if (rotation == 0) base else Bitmap.createBitmap(
                base, 0, 0, base.width, base.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true
            )
            val observation = checkNotNull(engine).analyze(upright)
            val progress = if (observation.issue != null) {
                current.rejected(observation.issue, now, observation.identityInterrupted)
            } else {
                current.accept(observation.vector, observation.yaw, now)
            }
            post { onProgress(progress.message) }
            if (progress.complete) {
                running.set(false)
                session = null
                post {
                    onRunning(false)
                    if (enabled.get()) onComplete(progress.template)
                }
            }
        } catch (_: Exception) {
            fail("Face processing failed. Close this screen and try again.")
        } finally {
            if (upright !== base) upright?.recycle()
            base?.recycle()
            image.close()
        }
    }

    private fun fail(message: String) {
        running.set(false)
        session = null
        post {
            onRunning(false)
            onProgress(message)
            onError(message)
        }
    }

    fun post(action: () -> Unit) = main.execute { if (!closed.get()) action() }
}
