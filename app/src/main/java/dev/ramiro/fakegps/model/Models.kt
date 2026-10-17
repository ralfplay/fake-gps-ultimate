package dev.ramiro.fakegps.model

/** Coordenada geográfica en grados. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Punto de la ruta. [stopSeconds] es el tiempo detenido al llegar (semáforo, parada).
 * [isCurrentLocation] marca el origen automático que sigue a la ubicación actual: no se borra ni se arrastra.
 */
data class Waypoint(
    val id: Long,
    val point: GeoPoint,
    val stopSeconds: Int = 0,
    val isCurrentLocation: Boolean = false,
)

/** Lugar donde ya se fijó la ubicación alguna vez; se guarda para volver rápido. */
data class SavedPlace(val id: Long, val name: String, val point: GeoPoint)

/** Punto de una ruta guardada (sin el origen, que siempre es la ubicación actual). */
data class SavedRoutePoint(val point: GeoPoint, val stopSeconds: Int)

/**
 * Ruta configurada que se puede volver a cargar: puntos, modo y velocidad.
 * Si [customStart] es true, el primer punto es el inicio elegido en el mapa; si no, el inicio es
 * la ubicación actual y [points] son solo las paradas y el destino.
 */
data class SavedRoute(
    val id: Long,
    val name: String,
    val points: List<SavedRoutePoint>,
    val travelMode: TravelMode,
    val speedKmh: Float,
    val customStart: Boolean = false,
)

/**
 * Nivel de tráfico: la velocidad real varía entre [minFactor] y [maxFactor] de la velocidad elegida,
 * con frenazos (detenido 5-20 s) unas [jamsPerMinute] veces por minuto.
 * [expectedFactor] es el promedio aproximado, para estimar el tiempo de la ruta.
 */
enum class TrafficLevel(
    val label: String,
    val minFactor: Double,
    val maxFactor: Double,
    val jamsPerMinute: Double,
    val expectedFactor: Double,
) {
    // expectedFactor medido en SimulationEngineTest (incluye arranques y frenazos).
    FREE("Libre", 0.85, 1.0, 0.0, 0.88),
    MODERATE("Moderado", 0.45, 0.9, 0.3, 0.6),
    HEAVY("Pesado", 0.15, 0.6, 1.0, 0.3),
}

/** FIXED: quedarse quieto en un punto. ROUTE: recorrer una ruta por calles. */
enum class SimMode(val label: String) {
    FIXED("Fija"),
    ROUTE("Ruta"),
}

enum class TravelMode(val label: String, val profile: String, val defaultSpeedKmh: Float) {
    CAR("Auto", "routed-car", 40f),
    BIKE("Bici", "routed-bike", 15f),
    FOOT("A pie", "routed-foot", 5f),
}

/** Ruta calculada: un tramo (lista de puntos sobre las calles) entre cada par de waypoints consecutivos. */
data class Route(
    val legs: List<List<GeoPoint>>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)
