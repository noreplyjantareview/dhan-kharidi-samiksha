package com.vsk.attendance.location

import com.vsk.attendance.data.Kendra
import com.vsk.attendance.util.GeoUtils

enum class AttendanceAction { NONE, CHECK_OUT, PULSE }

data class NearestKendra(val kendra: Kendra, val distM: Double, val inside: Boolean)

/**
 * Pure decision engine for duty attendance across one or more allowed kendras.
 * The server re-validates everything; this only decides when to call it.
 */
object AttendanceRules {

    fun nearest(kendras: List<Kendra>, lat: Double, lon: Double): NearestKendra? {
        if (kendras.isEmpty()) return null
        return kendras
            .map { k ->
                val d = GeoUtils.distanceM(lat, lon, k.latitude, k.longitude)
                NearestKendra(k, d, d <= k.radiusM)
            }
            .minByOrNull { it.distM }
    }

    /**
     * Check-in is a manual, face-verified action in the UI; this engine only
     * drives the automatic presence pulses and the day-end auto check-out.
     *
     * @param activeKendra the kendra the user checked into (drives day_end + pulse interval).
     * @param nowMinutesIST current IST minutes since midnight.
     * @param millisSinceLastPulse how long ago the last presence pulse was sent.
     */
    fun decide(
        kendras: List<Kendra>,
        lat: Double,
        lon: Double,
        checkedIn: Boolean,
        checkedOut: Boolean,
        activeKendra: Kendra?,
        nowMinutesIST: Int,
        millisSinceLastPulse: Long
    ): AttendanceAction {
        val near = nearest(kendras, lat, lon) ?: return AttendanceAction.NONE

        if (!checkedIn || checkedOut) return AttendanceAction.NONE

        val k = activeKendra ?: near.kendra
        val dayEnd = GeoUtils.hhmmToMinutes(k.dayEnd)
        if (dayEnd >= 0 && nowMinutesIST >= dayEnd) return AttendanceAction.CHECK_OUT

        val intervalMs = k.verifyIntervalMin.coerceAtLeast(1) * 60_000L
        if (millisSinceLastPulse >= intervalMs) return AttendanceAction.PULSE

        return AttendanceAction.NONE
    }
}
