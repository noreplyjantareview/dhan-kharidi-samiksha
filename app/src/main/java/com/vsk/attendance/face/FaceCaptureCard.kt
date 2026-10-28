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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun FaceCaptureCard(
    enrolling: Boolean,
    template: String? = null,
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
    var instruction by remember { mutableStateOf(FaceCopy.IDLE) }
    var accepted by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<FaceAnalyzer?>(null) }

    SideEffect { controller?.enabled?.set(enabled) }
    DisposableEffect(context, lifecycleOwner, enrolling, template) {
        ready = false
        running = false
        val main = ContextCompat.getMainExecutor(context)
        val analyzer = FaceAnalyzer(
            main = main,
            enrolling = enrolling,
            template = template,
            onProgress = { text, samples -> instruction = text; accepted = samples },
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
                // The mesh model drives the guidance, so it is loaded and warmed before the employee
                // presses Start; the heavier recognition pass then only runs on evidence frames.
                analyzer.guide = FaceGuide.get(context).apply { warmUp() }
                analyzer.engine = FaceEngine.get(context)
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    if (!analyzer.closed.get()) {
                        try {
                            provider = future.get()
                            analysis.setAnalyzer(analyzer.executor, analyzer)
                            provider!!.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                            ready = true
                            analyzer.publish(FaceCopy.READY_TO_START, 0)
                        } catch (_: Exception) {
                            analyzer.publish(FaceCopy.CAMERA_FAILED, 0)
                            analyzer.reportError(FaceCopy.CAMERA_FAILED)
                        }
                    }
                }, main)
            } catch (_: Exception) {
                analyzer.post {
                    analyzer.publish(FaceCopy.MODEL_FAILED, 0)
                    analyzer.reportError(FaceCopy.MODEL_FAILED)
                }
            } catch (_: LinkageError) {
                analyzer.post {
                    analyzer.publish(FaceCopy.ENGINE_UNAVAILABLE, 0)
                    analyzer.reportError(FaceCopy.ENGINE_UNAVAILABLE)
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(20.dp))
        )
        Text(
            if (enrolling && accepted in 1..4) "${instruction.english} · $accepted/5" else instruction.english,
            fontWeight = FontWeight.SemiBold
        )
        Text(instruction.hindi, color = Color(0xFF475569), fontSize = 15.sp)
        if (!ready || running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { controller?.start() },
            enabled = enabled && ready && !running,
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Column {
                Text(
                    if (running) "Checking face…" else if (enrolling) "Start face registration" else "Start face check"
                )
                Text(
                    if (running) "जाँच हो रही है…" else if (enrolling) "दर्ज शुरू करें" else "जाँच शुरू करें",
                    fontSize = 12.sp
                )
            }
        }
        Text(
            "Images are processed on this phone only. No face photos are saved or uploaded.",
            fontSize = 12.sp, color = Color(0xFF64748B)
        )
        Text("छवियाँ केवल इसी फ़ोन पर जाँची जाती हैं। चेहरे की फ़ोटो नहीं सहेजी या भेजी जाती है।",
            fontSize = 12.sp, color = Color(0xFF64748B))
    }
}

private class FaceAnalyzer(
    private val main: Executor,
    private val enrolling: Boolean,
    private val template: String?,
    private val onProgress: (FaceInstruction, Int) -> Unit,
    private val onRunning: (Boolean) -> Unit,
    private val onComplete: (String?) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {
    val executor = Executors.newSingleThreadExecutor()
    val closed = AtomicBoolean(false)
    val enabled = AtomicBoolean(true)
    private val running = AtomicBoolean(false)
    var guide: FaceGuide? = null
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
                session = FaceCheckSession(enrolling, template, now)
                deadline = now + FaceCheckSession.TIMEOUT_MS
                publish(FaceCopy.LOOK, 0)
            } catch (e: IllegalArgumentException) {
                fail(FaceInstruction(
                    e.message ?: "Face registration is not current. Ask your administrator for a reset.",
                    "चेहरा दर्ज नहीं है। प्रशासक से रीसेट माँगें"
                ))
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        var base: Bitmap? = null
        var upright: Bitmap? = null
        try {
            val now = SystemClock.elapsedRealtime()
            if (closed.get() || !enabled.get() || !running.get() || now - lastFrameAt < FRAME_INTERVAL_MS) return
            val current = session ?: return
            if (now > deadline) {
                fail(FaceCopy.TIMED_OUT)
                return
            }
            lastFrameAt = now
            base = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            upright = if (rotation == 0) base else Bitmap.createBitmap(
                base, 0, 0, base.width, base.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true
            )
            val model = checkNotNull(guide) { "Face guidance model is not loaded." }
            val engineModel = checkNotNull(engine) { "Face engine is not loaded." }
            val seen = model.read(upright, now)
            val progress = if (seen == null) {
                // Losing the face is a FaceLost interruption, not an identity break: a phone that
                // drops a frame must never wipe the samples the employee already earned.
                current.rejected(FaceCopy.FACE_LOST, now, FaceInterruption.FaceLost)
            } else {
                val guidance = current.onGuidance(seen, now)
                if (current.needsEvidence) {
                    val observation = engineModel.analyze(upright)
                    if (observation.issue != null) {
                        current.rejected(observation.issue, now, observation.interruption)
                    } else {
                        current.onEvidence(observation.vector, observation.yaw, now)
                    }
                } else {
                    guidance
                }
            }
            publish(progress.message, progress.accepted)
            if (progress.complete) {
                running.set(false)
                session = null
                post {
                    onRunning(false)
                    if (enabled.get()) onComplete(progress.template)
                }
            }
        } catch (_: Exception) {
            fail(FaceCopy.PROCESSING_FAILED)
        } finally {
            if (upright !== base) upright?.recycle()
            base?.recycle()
            image.close()
        }
    }

    fun publish(instruction: FaceInstruction, accepted: Int) = post { onProgress(instruction, accepted) }

    fun reportError(instruction: FaceInstruction) = post { onError(instruction.english) }

    private fun fail(instruction: FaceInstruction) {
        running.set(false)
        session = null
        post {
            onRunning(false)
            onProgress(instruction, 0)
            onError(instruction.english)
        }
    }

    fun post(action: () -> Unit) = main.execute { if (!closed.get()) action() }

    companion object {
        /**
         * A natural blink lasts 100-400ms, so guidance needs roughly eight frames a second. The
         * recognition and anti-spoof models are gated by the session's own evidence spacing instead.
         */
        private const val FRAME_INTERVAL_MS = 120L
    }
}
