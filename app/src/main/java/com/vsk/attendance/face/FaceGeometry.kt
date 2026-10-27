package com.vsk.attendance.face

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Identity check from face geometry only — no photo is ever stored or sent.
 * A template is the set of facial landmarks + jaw-contour points normalized
 * for translation, rotation and scale (eye midpoint = origin, eye distance = 1),
 * so it compares stable across distance, tilt and phone orientation.
 */
object FaceGeometry {

    const val MAX_AVG_DIST = 0.15
    const val MAX_POINT_DIST = 0.34
    private const val MIN_MATCHED_POINTS = 14

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

    /** Normalized geometry template as compact JSON, or null if eyes are missing. */
    fun buildTemplate(face: Face): String? {
        val raw = rawPoints(face)
        val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val norm = normalize(raw, le, re)
        val arr = JSONArray()
        for (p in norm) {
            if (p == null) arr.put(JSONObject.NULL)
            else arr.put(JSONArray().put(round(p[0])).put(round(p[1])))
        }
        return JSONObject().put("v", 1).put("pts", arr).toString()
    }

    data class MatchResult(val ok: Boolean, val score: Double?, val message: String?)

    fun match(template: String, face: Face): MatchResult {
        val stored = runCatching {
            val obj = JSONObject(template)
            val arr = obj.getJSONArray("pts")
            (0 until arr.length()).map { i ->
                if (arr.isNull(i)) null else {
                    val p = arr.getJSONArray(i)
                    floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
                }
            }
        }.getOrNull() ?: return MatchResult(false, null, "Stored face registration is invalid — contact admin.")

        val raw = rawPoints(face)
        val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            ?: return MatchResult(false, null, "Face not clear enough — try again.")
        val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            ?: return MatchResult(false, null, "Face not clear enough — try again.")
        val norm = normalize(raw, le, re)

        val n = minOf(stored.size, norm.size)
        var count = 0
        var sum = 0.0
        var max = 0.0
        for (i in 0 until n) {
            val s = stored[i] ?: continue
            val c = norm[i] ?: continue
            val d = hypot((s[0] - c[0]).toDouble(), (s[1] - c[1]).toDouble())
            sum += d
            if (d > max) max = d
            count++
        }
        if (count < MIN_MATCHED_POINTS)
            return MatchResult(false, null, "Face not clear enough — try again.")

        val avg = sum / count
        return if (avg <= MAX_AVG_DIST && max <= MAX_POINT_DIST) {
            MatchResult(true, avg, null)
        } else {
            MatchResult(false, avg, "Face does not match the registered user. Only the registered person can mark duty.")
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

    private fun round(v: Float): Double = Math.round(v * 10000.0) / 10000.0
}
