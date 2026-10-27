package com.vsk.attendance.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    private const val EARTH_RADIUS_M = 6371000.0
    private val IST = TimeZone.getTimeZone("Asia/Kolkata")

    /** Haversine great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** "HH:MM" -> minutes since midnight. Returns -1 if unparseable. */
    fun hhmmToMinutes(s: String?): Int {
        if (s.isNullOrBlank()) return -1
        val parts = s.split(":")
        if (parts.size < 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        return h * 60 + m
    }

    /** Current minutes since midnight in IST. */
    fun nowMinutesIST(): Int {
        val c = Calendar.getInstance(IST)
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Current time as HH:mm:ss in IST. */
    fun nowClockIST(): String {
        val c = Calendar.getInstance(IST)
        return String.format(
            "%02d:%02d:%02d",
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)
        )
    }
}
