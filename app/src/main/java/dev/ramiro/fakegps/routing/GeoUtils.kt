package dev.ramiro.fakegps.routing

import dev.ramiro.fakegps.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Rumbo en grados (0 = norte, sentido horario). */
    fun bearing(a: GeoPoint, b: GeoPoint): Float {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}

/** Polilínea con distancias acumuladas para ubicar rápidamente un punto a X metros del inicio. */
class PathGeometry(val points: List<GeoPoint>) {
    private val cumulative = DoubleArray(points.size)
    val length: Double

    init {
        require(points.isNotEmpty())
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + GeoUtils.distance(points[i - 1], points[i])
        }
        length = cumulative.last()
    }

    /** Trozo del camino entre [from] y [to] metros (para partir un tramo en un semáforo). */
    fun subPath(from: Double, to: Double): PathGeometry {
        val a = from.coerceIn(0.0, length)
        val b = to.coerceIn(a, length)
        val result = mutableListOf(positionAt(a).first)
        for (i in points.indices) {
            if (cumulative[i] > a && cumulative[i] < b) result += points[i]
        }
        result += positionAt(b).first
        return PathGeometry(result)
    }

    /** Devuelve la posición a [meters] del inicio y el rumbo del segmento (null si el segmento es degenerado). */
    fun positionAt(meters: Double): Pair<GeoPoint, Float?> {
        if (points.size == 1) return points[0] to null
        val d = meters.coerceIn(0.0, length)
        var i = cumulative.binarySearch(d)
        if (i < 0) i = -i - 2
        i = i.coerceIn(0, points.size - 2)

        val a = points[i]
        val b = points[i + 1]
        val segLen = cumulative[i + 1] - cumulative[i]
        if (segLen <= 0.0) return a to null
        val t = (d - cumulative[i]) / segLen
        val p = GeoPoint(
            a.latitude + (b.latitude - a.latitude) * t,
            a.longitude + (b.longitude - a.longitude) * t,
        )
        return p to GeoUtils.bearing(a, b)
    }
}
