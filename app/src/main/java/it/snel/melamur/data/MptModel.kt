package it.snel.melamur.data

import it.snel.melamur.map.Geo
import it.snel.melamur.map.Polyline
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Europe/Malta service-day helpers (see SQL-FORMAT.txt §2). */
object Malta {
    val ZONE: ZoneId = ZoneId.of("Europe/Malta")
    fun weekdayBit(d: LocalDate): Int = 1 shl (d.dayOfWeek.value - 1)
    /** Wall-clock instant for `minutes` after the start of service date [d]. */
    fun at(d: LocalDate, minutes: Int): LocalDateTime =
        d.atStartOfDay().plusMinutes(minutes.toLong()) // local wall clock (DST handled at display)
    fun hhmm(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)
}

/** A bus stop from the MPT dataset (stop table). */
data class Stop(
    val number: Int,
    val name: String,
    val locality: String?,
    val lat: Double,
    val lon: Double,
) {
    val x = Geo.x(lon)
    val y = Geo.y(lat)
    val display: String get() = if (locality.isNullOrBlank()) name else "$locality - $name"
    fun asBusStop() = BusStop(number.toString(), display, lat, lon, null, number.toString())
}

/** A route/line that serves a stop (from v_stop_routes joined to route). */
data class Line(val code: String, val name: String, val color: Int)

/** A scheduled departure from a stop. */
data class Departure(
    val routeCode: String,
    val towards: String,
    val tripId: Long,
    val directionIndex: Int,
    val minutes: Int,          // minutes since service-day midnight (may be >= 1440)
    val serviceDate: LocalDate,
) {
    val time: LocalDateTime get() = Malta.at(serviceDate, minutes)
}

/** One direction of a route with its road-snapped geometry and ordered stops. */
data class DirectionShape(
    val routeCode: String,
    val directionIndex: Int,
    val name: String,
    val color: Int,
    val shape: Polyline,
    val stopNumbers: List<String>,
)

/** A stop on a trip (for "next stop" / planner). */
data class TripStop(val seq: Int, val number: Int?, val name: String, val minutes: Int)

// ---- journey planner ------------------------------------------------------

sealed class Leg {
    abstract val fromName: String
    abstract val toName: String
}

data class WalkLeg(
    override val fromName: String, override val toName: String,
    val meters: Int, val seconds: Int,
    val fromLat: Double, val fromLon: Double, val toLat: Double, val toLon: Double,
) : Leg()

data class BusLeg(
    val routeCode: String, val color: Int, val towards: String, val tripId: Long,
    val boardNumber: Int, override val fromName: String, val boardMinutes: Int,
    val alightNumber: Int, override val toName: String, val alightMinutes: Int,
    val serviceDate: LocalDate, val intermediateStops: Int,
) : Leg() {
    val board: LocalDateTime get() = Malta.at(serviceDate, boardMinutes)
    val alight: LocalDateTime get() = Malta.at(serviceDate, alightMinutes)
}

data class Itinerary(val legs: List<Leg>, val departMinutes: Int, val arriveMinutes: Int, val serviceDate: LocalDate) {
    val depart: LocalDateTime get() = Malta.at(serviceDate, departMinutes)
    val arrive: LocalDateTime get() = Malta.at(serviceDate, arriveMinutes)
    val transfers: Int get() = legs.count { it is BusLeg }.let { if (it > 0) it - 1 else 0 }
    val busLegs: List<BusLeg> get() = legs.filterIsInstance<BusLeg>()
}
