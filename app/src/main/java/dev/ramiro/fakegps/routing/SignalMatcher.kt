package dev.ramiro.fakegps.routing

import dev.ramiro.fakegps.model.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot

/** Encuentra qué semáforos quedan sobre un tramo de la ruta y a qué distancia del inicio del tramo. */
object SignalMatcher {
    /** Un semáforo cuenta si está a menos de esto de la línea de la ruta. */
    private const val MAX_DISTANCE_M = 15.0

    /** Un cruce suele tener varios nodos de semáforo (uno por sentido): se agrupan los cercanos. */
    private const val MERGE_M = 40.0

    /** No se para en semáforos pegados a un punto de la ruta (ahí ya está la parada del punto). */
    private const val NEAR_WAYPOINT_M = 25.0

    private const val METERS_PER_DEG_LAT = 110_540.0
    private const val METERS_PER_DEG_LON = 111_320.0

    /** Distancias (en metros desde el inicio del tramo) donde hay un semáforo, ordenadas. */
    fun signalsOnLeg(leg: List<GeoPoint>, signals: List<GeoPoint>): List<Double> {
        if (leg.size < 2 || signals.isEmpty()) return emptyList()

        // Descarte rápido: solo semáforos dentro del rectángulo del tramo (con margen).
        val margin = 0.0005
        val minLat = leg.minOf { it.latitude } - margin
        val maxLat = leg.maxOf { it.latitude } + margin
        val minLon = leg.minOf { it.longitude } - margin
        val maxLon = leg.maxOf { it.longitude } + margin
        val candidates = signals.filter { it.latitude in minLat..maxLat && it.longitude in minLon..maxLon }
        if (candidates.isEmpty()) return emptyList()

        val cumulative = DoubleArray(leg.size)
        for (i in 1 until leg.size) cumulative[i] = cumulative[i - 1] + GeoUtils.distance(leg[i - 1], leg[i])
        val length = cumulative.last()

        val found = candidates.mapNotNull { signal ->
            var best = Double.MAX_VALUE
            var bestAlong = 0.0
            for (i in 0 until leg.size - 1) {
                val (dist, t) = distanceToSegment(signal, leg[i], leg[i + 1])
                if (dist < best) {
                    best = dist
                    bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
                }
            }
            bestAlong.takeIf { best <= MAX_DISTANCE_M }
        }.filter { it > NEAR_WAYPOINT_M && it < length - NEAR_WAYPOINT_M }.sorted()

        val merged = mutableListOf<Double>()
        for (d in found) {
            if (merged.isEmpty() || d - merged.last() > MERGE_M) merged += d
        }
        return merged
    }

    /** Distancia en metros de [p] al segmento a-b y la fracción (0..1) del punto más cercano. */
    private fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<Double, Double> {
        // Proyección local plana (válida para distancias cortas).
        val kx = METERS_PER_DEG_LON * cos(Math.toRadians(a.latitude))
        val bx = (b.longitude - a.longitude) * kx
        val by = (b.latitude - a.latitude) * METERS_PER_DEG_LAT
        val px = (p.longitude - a.longitude) * kx
        val py = (p.latitude - a.latitude) * METERS_PER_DEG_LAT
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by) to t
    }
}
