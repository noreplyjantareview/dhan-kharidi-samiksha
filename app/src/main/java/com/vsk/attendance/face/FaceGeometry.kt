package com.vsk.attendance.face

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Base64
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Identity check with no photo ever stored or sent. The template carries two
 * signals, both normalized for translation, rotation and scale (eye midpoint =
 * origin, eye distance = 1):
 *  - landmark + jaw-contour geometry, and
 *  - an aligned 32x32 high-pass grayscale signature of the face region.
 * Geometry alone is nearly identical for every frontal face, so the appearance
 * signature is what actually separates one person from another.
 */
object FaceGeometry {

    const val MAX_AVG_DIST = 0.15
    const val MAX_POINT_DIST = 0.34
    const val MIN_APP_COS = 0.45
    private const val MIN_MATCHED_POINTS = 14
    private const val GRID = 32
    private const val QUANT = 900f

    private val LANDMARK_TYPES = intArrayOf(
        FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.NOSE_BASE,
        FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_BOTTOM,
        FaceLandmark.LEFT_CHEEK, FaceLandmark.RIGHT_CHEEK, FaceLandmark.LEFT_EAR,
        FaceLandmark.RIGHT_EAR
    )
    private val CONTOUR_FRACTIONS = floatArrayOf(0f, 0.12f, 0.25f, 0.38f, 0.5f, 0.62f, 0.75f, 0.88f, 1f)

    /** Returns a problem message, or null when the frame is good enough to use. */
    fun qualityIssue(face: Face, imgW: Int, imgH: Int): String? {
        if (face.getLandmark(FaceLandmark.LEFT_EYE)?.position == null ||
            face.getLandmark(FaceLandmark.RIGHT_EYE)?.position == null
        ) return "Face not clear enough — improve the lighting and try again."
        val box = face.boundingBox
        if (box.width() < imgW * 0.22f || box.height() < imgH * 0.22f)
            return "Move closer so your face fills more of the frame."
        if (abs(face.headEulerAngleY) > 30f || abs(face.headEulerAngleZ) > 30f)
            return "Look straight at the camera."
        return null
    }

    /** True when the stored template predates the appearance signature. */
    fun needsReenroll(template: String): Boolean =
        runCatching { !JSONObject(template).has("app") }.getOrDefault(true)

    /** Normalized geometry + appearance template as compact JSON, or null if unusable. */
    fun buildTemplate(face: Face, bitmap: Bitmap): String? {
        val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val norm = normalize(rawPoints(face), le, re)
        val arr = JSONArray()
        for (p in norm) {
            if (p == null) arr.put(JSONObject.NULL)
            else arr.put(JSONArray().put(round(p[0])).put(round(p[1])))
        }
        val app = appearance(face, bitmap) ?: return null
        return JSONObject()
            .put("v", 2)
            .put("pts", arr)
            .put("app", Base64.encodeToString(quantize(app), Base64.NO_WRAP))
            .toString()
    }

    data class MatchResult(val ok: Boolean, val score: Double?, val message: String?)

    fun match(template: String, face: Face, bitmap: Bitmap): MatchResult {
        val parsed = runCatching {
            val obj = JSONObject(template)
            val arr = obj.getJSONArray("pts")
            val pts = (0 until arr.length()).map { i ->
                if (arr.isNull(i)) null else {
                    val p = arr.getJSONArray(i)
                    floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
                }
            }
            val app = if (obj.has("app")) decode(obj.getString("app")) else FloatArray(0)
            pts to app
        }.getOrNull() ?: return MatchResult(false, null, "Stored face registration is invalid — contact admin.")
        val storedPts = parsed.first
        val storedApp = parsed.second

        if (storedApp.isEmpty())
            return MatchResult(false, null, "Face registration is the old type — sign out and register again.")

        val raw = rawPoints(face)
        val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            ?: return MatchResult(false, null, "Face not clear enough — try again.")
        val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            ?: return MatchResult(false, null, "Face not clear enough — try again.")
        val norm = normalize(raw, le, re)

        val n = minOf(storedPts.size, norm.size)
        var count = 0
        var sum = 0.0
        var maxD = 0.0
        for (i in 0 until n) {
            val s = storedPts[i] ?: continue
            val c = norm[i] ?: continue
            val d = hypot((s[0] - c[0]).toDouble(), (s[1] - c[1]).toDouble())
            sum += d
            if (d > maxD) maxD = d
            count++
        }
        if (count < MIN_MATCHED_POINTS)
            return MatchResult(false, null, "Face not clear enough — try again.")

        val avg = sum / count
        if (avg > MAX_AVG_DIST || maxD > MAX_POINT_DIST)
            return MatchResult(false, avg, "Face does not match the registered user. Only the registered person can mark duty.")

        val live = appearance(face, bitmap)
            ?: return MatchResult(false, null, "Face not clear enough — try again.")
        val cos = bestCos(storedApp, live)
        return if (cos >= MIN_APP_COS) {
            MatchResult(true, cos, null)
        } else {
            MatchResult(
                false, cos,
                "Face does not match the registered user. Only the registered person can mark duty. (score %.2f)".format(cos)
            )
        }
    }

    private fun rawPoints(face: Face): List<PointF?> {
        val pts = ArrayList<PointF?>(LANDMARK_TYPES.size + CONTOUR_FRACTIONS.size)
        for (t in LANDMARK_TYPES) pts.add(face.getLandmark(t)?.position)
        val contour = face.getContour(FaceContour.FACE)?.points ?: emptyList()
        for (f in CONTOUR_FRACTIONS) {
            if (contour.isEmpty()) { pts.add(null); continue }
            val idx = (f * (contour.size - 1)).toInt().coerceIn(0, contour.size - 1)
            pts.add(contour[idx])
        }
        return pts
    }

    private fun normalize(points: List<PointF?>, le: PointF, re: PointF): List<FloatArray?> {
        val cx = (le.x + re.x) / 2f
        val cy = (le.y + re.y) / 2f
        val scale = hypot(re.x - le.x, re.y - le.y)
        if (scale <= 1f) return points.map { null }
        val ang = atan2((re.y - le.y).toDouble(), (re.x - le.x).toDouble())
        val cos = cos(ang).toFloat()
        val sin = sin(ang).toFloat()
        return points.map { p ->
            if (p == null) null else {
                val dx = (p.x - cx) / scale
                val dy = (p.y - cy) / scale
                floatArrayOf(dx * cos + dy * sin, -dx * sin + dy * cos)
            }
        }
    }

    /**
     * Samples a 32x32 luminance grid over the face region in eye-relative
     * coordinates, then high-pass filters and unit-normalizes it so the vector
     * is stable across lighting and distance but specific to one face.
     */
    private fun appearance(face: Face, bitmap: Bitmap): FloatArray? {
        val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val scale = hypot((re.x - le.x).toDouble(), (re.y - le.y).toDouble()).toFloat()
        if (scale <= 4f) return null
        val cx = (le.x + re.x) / 2f
        val cy = (le.y + re.y) / 2f
        val ang = atan2((re.y - le.y).toDouble(), (re.x - le.x).toDouble())
        val cosA = cos(ang).toFloat()
        val sinA = sin(ang).toFloat()
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val raw = FloatArray(GRID * GRID)
        for (r in 0 until GRID) {
            val v = -1.3f + 3.2f * r / (GRID - 1)
            for (c in 0 until GRID) {
                val u = -1.6f + 3.2f * c / (GRID - 1)
                val x = cx + (u * cosA - v * sinA) * scale
                val y = cy + (u * sinA + v * cosA) * scale
                raw[r * GRID + c] = lumAt(px, w, h, x, y)
            }
        }
        return highPassUnit(raw)
    }

    private fun lumAt(px: IntArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xi = x.toInt().coerceIn(0, w - 1)
        val yi = y.toInt().coerceIn(0, h - 1)
        val c = px[yi * w + xi]
        return 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
    }

    private fun highPassUnit(raw: FloatArray): FloatArray? {
        val tmp = FloatArray(GRID * GRID)
        val blur = FloatArray(GRID * GRID)
        for (r in 0 until GRID) for (c in 0 until GRID) {
            var s = 0f
            for (d in -2..2) s += raw[r * GRID + (c + d).coerceIn(0, GRID - 1)]
            tmp[r * GRID + c] = s / 5f
        }
        for (r in 0 until GRID) for (c in 0 until GRID) {
            var s = 0f
            for (d in -2..2) s += tmp[(r + d).coerceIn(0, GRID - 1) * GRID + c]
            blur[r * GRID + c] = s / 5f
        }
        val hp = FloatArray(GRID * GRID) { raw[it] - blur[it] }
        var mean = 0f
        for (v in hp) mean += v
        mean /= hp.size
        var ss = 0.0
        for (i in hp.indices) {
            hp[i] -= mean
            ss += hp[i] * hp[i]
        }
        val norm = sqrt(ss).toFloat()
        if (norm < 1f) return null
        for (i in hp.indices) hp[i] /= norm
        return hp
    }

    private fun quantize(unit: FloatArray): ByteArray =
        ByteArray(unit.size) { i -> (unit[i] * QUANT).toInt().coerceIn(-128, 127).toByte() }

    private fun decode(b64: String): FloatArray {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return FloatArray(bytes.size) { i -> bytes[i].toFloat() / QUANT }
    }

    /** Cosine similarity, tolerant of small alignment shifts (±2 grid cells). */
    private fun bestCos(stored: FloatArray, live: FloatArray): Double {
        var best = -1.0
        for (dr in intArrayOf(-2, 0, 2)) for (dc in intArrayOf(-2, 0, 2)) {
            var dot = 0.0
            var ss = 0.0
            for (r in 0 until GRID) {
                val sr = r + dr
                if (sr < 0 || sr >= GRID) continue
                for (c in 0 until GRID) {
                    val sc = c + dc
                    if (sc < 0 || sc >= GRID) continue
                    val s = stored[sr * GRID + sc].toDouble()
                    dot += s * live[r * GRID + c]
                    ss += s * s
                }
            }
            if (ss > 0.0) best = max(best, dot / sqrt(ss))
        }
        return best
    }

    private fun round(v: Float): Double = Math.round(v * 10000.0) / 10000.0
}
