package com.vsk.attendance.face

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
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
import com.vsk.attendance.BuildConfig
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live face check screen. There is deliberately no button: the camera opens, the guidance starts on
 * its own and the check runs until a blink completes it, exactly like the browser prototype the
 * blink flow was validated on. Field staff could not find, read and press a button in time.
 */
@Composable
fun FaceCaptureCard(
    enrolling: Boolean,
    template: String? = null,
    enabled: Boolean = true,
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
    var failed by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<FaceAnalyzer?>(null) }

    SideEffect { controller?.setEnabled(enabled) }
    DisposableEffect(context, lifecycleOwner, enrolling, template) {
        ready = false
        failed = false
        val main = ContextCompat.getMainExecutor(context)
        val analyzer = FaceAnalyzer(
            main = main,
            enrolling = enrolling,
            template = template,
            onProgress = { text, samples -> instruction = text; accepted = samples },
            onComplete = { completion(it) },
            onError = { message -> failed = true; error(message) }
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
                // The mesh model drives the guidance, so it is loaded and warmed before the first
                // frame; the heavier recognition pass then only runs on evidence frames.
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
                            analyzer.cameraReady()
                        } catch (e: Exception) {
                            analyzer.hardFail(FaceCopy.CAMERA_FAILED, e)
                        }
                    }
                }, main)
            } catch (e: Exception) {
                analyzer.hardFail(FaceCopy.MODEL_FAILED, e)
            } catch (e: LinkageError) {
                analyzer.hardFail(FaceCopy.ENGINE_UNAVAILABLE, e)
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
        if (!ready && !failed) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            "The check runs by itself. Images are processed on this phone only. " +
                "No face photos are saved or uploaded.",
            fontSize = 12.sp, color = Color(0xFF64748B)
        )
        Text(
            "जाँच अपने आप चलती है। छवियाँ केवल इसी फ़ोन पर जाँची जाती हैं। " +
                "चेहरे की फ़ोटो नहीं सहेजी या भेजी जाती है।",
            fontSize = 12.sp, color = Color(0xFF64748B)
        )
    }
}

private class FaceAnalyzer(
    private val main: Executor,
    private val enrolling: Boolean,
    private val template: String?,
    private val onProgress: (FaceInstruction, Int) -> Unit,
    private val onComplete: (String?) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {
    val executor = Executors.newSingleThreadExecutor()
    val closed = AtomicBoolean(false)
    val enabled = AtomicBoolean(true)
    var guide: FaceGuide? = null
    var engine: FaceEngine? = null
    @Volatile private var ready = false
    private val running = AtomicBoolean(false)

    /** Once a hard failure is reported the card is dead until it is recreated; no error loop. */
    private val stopped = AtomicBoolean(false)
    @Volatile private var session: FaceCheckSession? = null
    private var deadline = 0L
    private var lastFrameAt = 0L
    private var frameFailures = 0
    private var windowRestarts = 0

    /** Called from the UI once the camera is bound, and whenever the parent re-enables the card. */
    fun setEnabled(on: Boolean) {
        if (enabled.getAndSet(on) == on) return
        executor.execute {
            if (on) { if (ready && !running.get() && !stopped.get()) begin() } else stop()
        }
    }

    fun cameraReady() {
        ready = true
        executor.execute { if (enabled.get() && !running.get() && !stopped.get()) begin() }
    }

    private fun publish(instruction: FaceInstruction, accepted: Int) =
        post { onProgress(instruction, accepted) }

    /**
     * Stops the check for good and says why. A field failure that only says "try again" is the bug
     * this replaces: debug builds carry the real cause so one screenshot is enough to fix it.
     */
    fun hardFail(instruction: FaceInstruction, cause: Throwable) {
        Log.e(TAG, "face check stopped: $instruction", cause)
        stopped.set(true)
        enabled.set(false)
        stop()
        val shown = instruction.withCause(cause)
        post {
            onProgress(shown, 0)
            onError(shown.english)
        }
    }

    private fun post(action: () -> Unit) = main.execute { if (!closed.get()) action() }

    private fun begin() {
        val now = SystemClock.elapsedRealtime()
        session = try {
            FaceCheckSession(enrolling, template, now)
        } catch (e: IllegalArgumentException) {
            hardFail(
                FaceInstruction(
                    e.message ?: "Face registration is not current. Ask your administrator",
                    "चेहरा दर्ज नहीं है। प्रशासक से रीसेट माँगें"
                ), e
            )
            return
        }
        deadline = now + FaceCheckSession.TIMEOUT_MS
        frameFailures = 0
        running.set(true)
        publish(
            if (windowRestarts >= SLOW_AFTER_WINDOWS) FaceCopy.SLOW else FaceCopy.READY_TO_START, 0
        )
    }

    private fun stop() {
        running.set(false)
        session = null
    }

    override fun analyze(image: ImageProxy) {
        var base: Bitmap? = null
        var upright: Bitmap? = null
        try {
            val now = SystemClock.elapsedRealtime()
            if (closed.get() || !enabled.get() || !running.get()) return
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            if (now > deadline) {
                // Nobody is blamed for a slow blink: the window simply starts again, and after a
                // couple of empty ones the line changes from "get ready" to something actionable.
                windowRestarts++
                begin()
                return
            }
            lastFrameAt = now
            val current = session ?: return
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
            frameFailures = 0
            // Leaving the anchor stage is the only progress a verification can show, so the slow
            // hint keys off it rather than the sample count.
            if (current.currentStage != FaceStage.Align) windowRestarts = 0
            publish(progress.message, progress.accepted)
            if (progress.complete) {
                stop()
                post { if (enabled.get()) onComplete(progress.template) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "face frame failed", e)
            // A single bad frame is not a failed registration. Only a run of them stops the check,
            // and then the reason is reported instead of being swallowed.
            frameFailures++
            if (frameFailures >= FRAME_FAILURES_ALLOWED) hardFail(FaceCopy.PROCESSING_FAILED, e)
        } finally {
            if (upright !== base) upright?.recycle()
            base?.recycle()
            image.close()
        }
    }

    private companion object {
        /**
         * A natural blink lasts 100-400ms, so guidance needs roughly eight frames a second. The
         * recognition and anti-spoof models are gated by the session's own evidence spacing instead.
         */
        const val FRAME_INTERVAL_MS = 120L

        /** Consecutive frame exceptions before the check gives up: enough for a hiccup, not a hang. */
        const val FRAME_FAILURES_ALLOWED = 4

        /** A cause line is truncated, then the plain instruction plus a tag is kept under the limit. */
        const val CAUSE_MAX_LENGTH = 120
        const val INSTRUCTION_MAX_LENGTH = 220

        /** Silent 120-second windows with no anchor before the line changes to the slow hint. */
        const val SLOW_AFTER_WINDOWS = 2

        const val TAG = "FaceCheck"
    }
}

/**
 * Field staff read the plain line; a debug build appends the root cause, because "try again" on its
 * own is not something an administrator can act on or describe back to a developer.
 */
private fun FaceInstruction.withCause(cause: Throwable): FaceInstruction =
    if (!BuildConfig.DEBUG) this else FaceInstruction(
        "$english [${causeTag(cause)}]".take(INSTRUCTION_MAX_LENGTH), hindi
    )

private fun causeTag(cause: Throwable): String {
    val root = generateSequence(cause) { it.cause }.lastOrNull() ?: cause
    val message = root.message?.replace('\n', ' ')?.trim()?.take(CAUSE_MAX_LENGTH)
    val tag = root.javaClass.simpleName + if (message.isNullOrBlank()) "" else ": $message"
    return tag.take(CAUSE_MAX_LENGTH + 40)
}
