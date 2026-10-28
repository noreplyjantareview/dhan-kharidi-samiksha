package com.vsk.attendance.face

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Development harness: the phone's face engine, unchanged, driving a laptop webcam.
 *
 * The engine files it compiles with - session, geometry, blink tracker, mesh print, quality gate,
 * instruction copy and the stored template - have no Android imports, so every decision this server
 * makes is the decision the phone makes, including the thresholds. Only the Android half is replaced:
 * the camera and MediaPipe run in the browser against the APK's own `face_landmarker.task` bytes and
 * the same tasks-vision 1.1.1 library the app pins, and the frame loop mirrors `FaceCaptureCard`.
 *
 * Frames arrive over loopback and live in memory only. Nothing is written to disk or sent anywhere
 * else, which is the same promise the app makes to the employee.
 */
private const val MAGIC = 0x46414345 // "FACE"

/** Mirrors the private constants in FaceCaptureCard's analyzer. */
private const val REGION_MARGIN = 0.08f
private const val MIN_LANDMARKS = 468
private const val FRAME_FAILURES_ALLOWED = 4
private const val SLOW_AFTER_WINDOWS = 2

private val lock = Any()
private var mode = "enroll"
private var storedTemplate: String? = null
private var session: FaceCheckSession? = null
private var deadline = 0L
private var windowRestarts = 0
private var frameFailures = 0
private var stopped = false
private var finished = false
private var hardCause: String? = null
private var scratch = IntArray(0)

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 8765
    val root = File(args.getOrNull(1) ?: findRepoRoot())
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    // One thread, like the phone's single analysis executor: frames must not interleave the session.
    server.executor = Executors.newSingleThreadExecutor()
    server.createContext("/frame") { exchange -> reply(exchange) { frame(readAll(exchange)) } }
    server.createContext("/control") { exchange -> reply(exchange) { control(readAll(exchange)) } }
    server.createContext("/state") { exchange -> reply(exchange) { state() } }
    server.createContext("/face_landmarker.task") { exchange ->
        sendFile(exchange, File(root, "app/src/main/assets/face/face_landmarker.task"), "application/octet-stream")
    }
    server.createContext("/") { exchange ->
        if (exchange.requestMethod == "GET" && exchange.requestURI.path == "/") {
            sendFile(exchange, File(root, "tools/face-harness/index.html"), "text/html; charset=utf-8")
        } else {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
    }
    server.start()
    println("Face harness: http://localhost:$port")
    println("Repo root:    ${root.absolutePath}")
    println("Open it in Chrome, allow the camera. Ctrl+C stops the server.")
}

private fun findRepoRoot(): String {
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null) {
        if (File(dir, "app/src/main/assets/face/face_landmarker.task").isFile) return dir.absolutePath
        dir = dir.parentFile
    }
    return System.getProperty("user.dir")
}

private fun readAll(exchange: HttpExchange): ByteArray =
    exchange.requestBody.use { it.readBytes() }

private fun reply(exchange: HttpExchange, body: () -> String) {
    val text = try {
        body()
    } catch (e: Exception) {
        // Named, not swallowed: a harness that hides its own failure is worse than no harness.
        """{"ok":false,"error":"${escape(e.javaClass.simpleName)}: ${escape(e.message ?: "")}"}"""
    }
    val bytes = text.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.responseHeaders.add("Cache-Control", "no-store")
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun sendFile(exchange: HttpExchange, file: File, contentType: String) {
    if (!file.isFile) {
        exchange.sendResponseHeaders(404, -1)
        exchange.close()
        return
    }
    val bytes = file.readBytes()
    exchange.responseHeaders.add("Content-Type", contentType)
    exchange.responseHeaders.add("Cache-Control", "no-store")
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

// ---------- the frame loop, mirroring FaceAnalyzer.analyze ----------

private fun frame(body: ByteArray): String {
    val buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.int != MAGIC) throw IllegalArgumentException("frame is missing its header")
    val now = buffer.long
    val frameWidth = buffer.int
    val frameHeight = buffer.int
    val landmarkCount = buffer.int
    val expressionClosure = buffer.float
    val xy = FloatArray(landmarkCount * 2) { buffer.float }
    val pixelBytes = frameWidth * frameHeight * 4
    if (buffer.remaining() < pixelBytes) {
        throw IllegalArgumentException("frame pixels are short by ${pixelBytes - buffer.remaining()} bytes")
    }
    // Read byte by byte so the layout is exactly the RGBA order the canvas hands over, whatever the
    // machine does with the header ints. The alpha channel is dropped: the meter works in luma.
    val pixels = IntArray(frameWidth * frameHeight) {
        val red = buffer.get().toInt() and 0xFF
        val green = buffer.get().toInt() and 0xFF
        val blue = buffer.get().toInt() and 0xFF
        buffer.get()
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    return synchronized(lock) { analyze(now, pixels, frameWidth, frameHeight, xy, expressionClosure) }
}

/** One frame through the real session, with the card's own failure counting and slow-check hint. */
private fun analyze(
    now: Long,
    pixels: IntArray,
    frameWidth: Int,
    frameHeight: Int,
    xy: FloatArray,
    expressionClosure: Float
): String {
    if (stopped || hardCause != null) {
        return frameJson(
            FaceCheckProgress(FaceInstruction(hardCause ?: "Face check stopped.", hardCause.orEmpty())),
            null, now, idle = true
        )
    }
    if (finished) {
        // The phone destroys the card when the check completes; holding the result here is what stops
        // a page that forgot to stop its loop from quietly enrolling a second template.
        return frameJson(
            FaceCheckProgress(FaceCopy.COMPLETE, FaceCheckSession.SAMPLES_REQUIRED, complete = true),
            null, now, idle = true
        )
    }
    val current = ensureSession(now) ?: return frameJson(
        FaceCheckProgress(FaceInstruction(hardCause.orEmpty(), hardCause.orEmpty())), null, now, idle = true
    )
    val meshReading = toReading(xy, expressionClosure, frameWidth, frameHeight)
    return try {
        var stats: FrameStats? = null
        var quality: FaceInstruction? = null
        val progress = if (meshReading == null) {
            current.rejected(FaceCopy.FACE_LOST, now, FaceInterruption.FaceLost)
        } else {
            val guided = current.onGuidance(meshReading.guidance, now)
            if (current.needsEvidence) {
                stats = measureRegion(pixels, frameWidth, frameHeight, meshReading.signature.region)
                val issue = FaceFrameQuality.issue(stats)
                quality = issue
                if (issue != null) {
                    current.rejected(issue, now, FaceInterruption.Quality)
                } else {
                    current.onEvidence(meshReading.signature.vector, meshReading.signature.yaw, now)
                }
            } else {
                guided
            }
        }
        frameFailures = 0
        if (current.currentStage != FaceStage.Align) windowRestarts = 0
        if (progress.complete) {
            progress.template?.let { storedTemplate = it }
            finished = true
            stopSession()
        }
        val out = frameJson(progress, meshReading?.signature, now, idle = false, stats = stats, quality = quality)
        if (progress.complete || progress.message.english != lastLine) {
            lastLine = progress.message.english
            println(out)
        }
        out
    } catch (e: Exception) {
        // A single bad frame is not a failed check; four in a row is, and then it names itself.
        frameFailures++
        if (frameFailures >= FRAME_FAILURES_ALLOWED) stopped = true
        val message = "${e.javaClass.simpleName}: ${e.message}"
        println("frame failure $frameFailures: $message")
        val failed = FaceCheckProgress(
            FaceInstruction(
                us("%s [after %d frames]", FaceCopy.PROCESSING_FAILED.english, frameFailures),
                FaceCopy.PROCESSING_FAILED.hindi
            )
        )
        appendCause(frameJson(failed, meshReading?.signature, now, idle = true), message)
    }
}

/** The most recent visible line, so the console records changes instead of the frame rate. */
private var lastLine = ""

/** Kept out of the JSON builder's signature: the last frame cause, for the next state read. */
private var lastCause: String? = null

private fun appendCause(json: String, cause: String): String {
    lastCause = cause
    return json.removeSuffix("}") + ",\"cause\":\"${escape(cause)}\"}"
}

/** What FaceGuide.read builds from one mesh pass: guidance plus the shape print, or nothing at all. */
private fun toReading(xy: FloatArray, expressionClosure: Float, widthPx: Int, heightPx: Int): FaceReading? {
    if (xy.size / 2 < MIN_LANDMARKS) return null
    val signature = FaceMeshSignature.of(xy, heightPx.toFloat() / widthPx) ?: return null
    return FaceReading(
        guidance = FaceGuidance(
            openRatio = FaceGeometry.eyeOpenness(xy),
            expressionClosure = expressionClosure,
            framing = FaceGeometry.framing(xy, widthPx)
        ),
        signature = signature
    )
}

private fun measureRegion(pixels: IntArray, frameWidth: Int, frameHeight: Int, region: FrameRegion): FrameStats? {
    val left = ((region.left - REGION_MARGIN) * frameWidth).toInt().coerceAtLeast(0)
    val top = ((region.top - REGION_MARGIN) * frameHeight).toInt().coerceAtLeast(0)
    val right = ((region.right + REGION_MARGIN) * frameWidth).toInt().coerceAtMost(frameWidth)
    val bottom = ((region.bottom + REGION_MARGIN) * frameHeight).toInt().coerceAtMost(frameHeight)
    val width = right - left
    val height = bottom - top
    if (width < 8 || height < 8) return null
    // The phone reads the region through Bitmap.getPixels, which never packs a row tighter than four
    // pixels, so the harness lays its copy out with the same stride and hands over the same shape.
    val stride = maxOf(width, (width + 3) and 3.inv())
    if (scratch.size < height * stride) scratch = IntArray(height * stride)
    for (row in 0 until height) {
        val from = (top + row) * frameWidth + left
        System.arraycopy(pixels, from, scratch, row * stride, width)
    }
    return FaceFrameQuality.measure(scratch, width, height, stride)
}

private fun ensureSession(now: Long): FaceCheckSession? {
    val existing = session
    if (existing != null && now <= deadline) return existing
    if (existing != null) windowRestarts++
    begin(now)
    return session
}

private fun begin(now: Long) {
    // On the phone a screen checks the stored registration before the card is ever shown, so a verify
    // run that reaches the session with nothing readable is a harness mistake, not an engine fault.
    // FaceCheckSession answers with its own IllegalArgumentException either way; naming the difference
    // here is what keeps it from being diagnosed as a broken threshold.
    if (mode != "enroll" && FaceTemplate.decode(storedTemplate) == null) {
        hardCause = if (storedTemplate == null) {
            "No stored registration to verify against - press Register first."
        } else {
            "Stored registration is not current - re-enrol."
        }
        stopped = true
        return
    }
    session = try {
        FaceCheckSession(mode == "enroll", storedTemplate, now)
    } catch (e: IllegalArgumentException) {
        // Same as the phone: the registration is not current, and the real reason is the message.
        hardCause = e.message
        stopped = true
        null
    }
    if (session != null) {
        deadline = now + FaceCheckSession.TIMEOUT_MS
        frameFailures = 0
    }
}

private fun stopSession() {
    session = null
    deadline = 0L
}

private fun state(): String = synchronized(lock) {
    """{"ok":true,"mode":"${escape(mode)}","hasTemplate":${storedTemplate != null},""" +
        """"templateChars":${storedTemplate?.length ?: 0},"threshold":${FaceTemplate.MATCH_THRESHOLD},""" +
        """"model":"${escape(FaceTemplate.MODEL)}","dimensions":${FaceTemplate.DIMENSIONS},""" +
        """"samples":${FaceCheckSession.SAMPLES_REQUIRED},"windowRestarts":$windowRestarts,""" +
        """"frameFailures":$frameFailures,"stopped":$stopped,"finished":$finished,""" +
        """"cause":${if (lastCause == null) "null" else "\"${escape(lastCause!!)}\""},""" +
        """"hardCause":${if (hardCause == null) "null" else "\"${escape(hardCause!!)}\""}}"""
}

private fun control(body: ByteArray): String {
    val text = body.toString(Charsets.UTF_8)
    val action = "action\"?\\s*:\\s*\"([a-zA-Z]+)\"".toRegex().find(text)?.groupValues?.get(1) ?: ""
    synchronized(lock) {
        when (action) {
            "register" -> { mode = "enroll"; resetRun() }
            "verify" -> { mode = "verify"; resetRun() }
            "clearTemplate" -> { storedTemplate = null; mode = "enroll"; resetRun() }
            // On the phone a staff member with no readable registration is sent to enrolment rather
            // than to recognition, so Restart does the same: otherwise Restart repeats a refusal the
            // employee can never act on, and the harness dead-ends on its own button.
            "restart" -> { if (FaceTemplate.decode(storedTemplate) == null) mode = "enroll"; resetRun() }
            "" -> throw IllegalArgumentException("control needs an action")
            else -> throw IllegalArgumentException("unknown control action: $action")
        }
        return state()
    }
}

private fun resetRun() {
    stopped = false
    finished = false
    hardCause = null
    frameFailures = 0
    windowRestarts = 0
    lastCause = null
    stopSession()
}

// ---------- responses ----------

private fun frameJson(
    progress: FaceCheckProgress,
    signature: MeshSignature?,
    now: Long,
    idle: Boolean,
    stats: FrameStats? = null,
    quality: FaceInstruction? = null
): String {
    val stage = session?.currentStage?.name ?: "Idle"
    val shown = if (progress.score >= 0f) {
        us("%s [mesh %.5f needs %.5f]", progress.message.english, progress.score, FaceTemplate.MATCH_THRESHOLD)
    } else {
        progress.message.english
    }
    val reference = storedTemplate?.let { FaceTemplate.decode(it) }
    val cosines = if (reference != null && signature != null) {
        reference.samples.joinToString(",") { us("%.5f", FaceTemplate.cosine(it, signature.vector)) }
    } else ""
    val matches = if (reference != null && signature != null) reference.matches(signature.vector).toString() else "null"
    val slowHint = !idle && windowRestarts >= SLOW_AFTER_WINDOWS && stage == FaceStage.Align.name
    return buildString {
        fun field(name: String, value: String) {
            append(",\"").append(name).append("\":").append(value)
        }
        append("{\"ok\":true")
        field("now", now.toString())
        field("stage", "\"$stage\"")
        field("mode", "\"$mode\"")
        field("en", "\"${escape(progress.message.english)}\"")
        field("hi", "\"${escape(progress.message.hindi)}\"")
        field("line", "\"${escape(shown)}\"")
        field("accepted", progress.accepted.toString())
        field("complete", progress.complete.toString())
        field("score", if (progress.score.isFinite()) progress.score.toString() else "null")
        field("matched", matches)
        field("sampleScores", "[$cosines]")
        field("slowHint", slowHint.toString())
        field("yaw", signature?.yaw?.toString() ?: "null")
        field("blinkMargin", session?.blinkMargin?.toString() ?: "null")
        field(
            "stats",
            if (stats == null) "null" else us(
                "{\"brightness\":%.1f,\"contrast\":%.1f,\"edges\":%.2f}",
                stats.brightness, stats.contrast, stats.edges
            )
        )
        field("quality", if (quality == null) "null" else "\"${escape(quality.english)}\"")
        field(
            "templateChars",
            if (progress.complete) (progress.template?.length ?: storedTemplate?.length ?: 0).toString() else "0"
        )
        append("}")
    }
}

/** Locale.US because these numbers go into JSON, not into a sentence. */
private fun us(pattern: String, vararg values: Any): String = String.format(java.util.Locale.US, pattern, *values)

private fun escape(value: String): String = value
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
    .filter { it.code >= 32 || it == '\t' }
