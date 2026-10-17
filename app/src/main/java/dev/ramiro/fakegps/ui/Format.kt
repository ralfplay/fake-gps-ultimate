package dev.ramiro.fakegps.ui

import dev.ramiro.fakegps.model.GeoPoint
import dev.ramiro.fakegps.model.SavedRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal fun formatCoords(p: GeoPoint): String =
    String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)

internal fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m"
    else String.format(Locale.getDefault(), "%.1f km", meters / 1000)

internal fun formatDuration(seconds: Double): String {
    val total = seconds.roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "$h h $m min"
        m > 0 -> if (s > 0) "$m min $s s" else "$m min"
        else -> "$s s"
    }
}

internal fun formatDate(millis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(millis))

internal fun routeDescription(route: SavedRoute): String {
    val stops = route.points.dropLast(1).sumOf { it.stopSeconds }
    val count = route.points.size
    val pointsText = if (count == 1) "1 punto" else "$count puntos"
    val stopsText = if (stops > 0) " · ${formatDuration(stops.toDouble())} de paradas" else ""
    val start = if (route.customStart) "inicio propio" else "desde mi ubicación"
    return "$pointsText · ${route.travelMode.label} · ${route.speedKmh.roundToInt()} km/h$stopsText · $start"
}

internal fun routeSummary(ui: UiState, currentLocationKnown: Boolean): String = when {
    ui.routeLoading -> "Calculando ruta…"
    ui.routeError != null -> "Error: ${ui.routeError}"
    ui.route != null -> "${formatDistance(ui.route.distanceMeters)} · ~${formatDuration(ui.estimatedSeconds ?: 0.0)}"
    ui.waypoints.isEmpty() && !ui.originFromCurrentLocation -> "Toca el mapa para poner el inicio"
    ui.waypoints.isEmpty() && !currentLocationKnown -> "Buscando tu ubicación…"
    ui.waypoints.isEmpty() -> "Toca el mapa para poner el inicio"
    else -> "Toca el mapa para añadir el destino"
}
