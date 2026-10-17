package dev.ramiro.fakegps.routing

import dev.ramiro.fakegps.model.Route
import dev.ramiro.fakegps.model.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import dev.ramiro.fakegps.model.GeoPoint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calcula rutas que siguen calles usando el servidor OSRM público de FOSSGIS
 * (routing.openstreetmap.de). Gratis y sin API key; pensado para uso personal moderado.
 */
object OsrmClient {
    private const val BASE_URL = "https://routing.openstreetmap.de"

    suspend fun route(points: List<GeoPoint>, mode: TravelMode): Route = withContext(Dispatchers.IO) {
        require(points.size >= 2)
        val coords = points.joinToString(";") { "${it.longitude},${it.latitude}" }
        val url = URL("$BASE_URL/${mode.profile}/route/v1/driving/$coords?overview=false&steps=true&geometries=geojson")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "FakeGpsRutas/0.1 (uso personal)")
        }
        val body = try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() }
                ?: throw IOException("Respuesta vacía (HTTP ${conn.responseCode})")
        } finally {
            conn.disconnect()
        }

        parse(body)
    }

    private fun parse(body: String): Route {
        val json = JSONObject(body)
        val code = json.optString("code")
        if (code != "Ok") {
            val msg = json.optString("message").ifBlank { code }
            throw IOException(if (code == "NoRoute") "No se encontró ruta entre los puntos" else msg)
        }

        val route = json.getJSONArray("routes").getJSONObject(0)
        val legsJson = route.getJSONArray("legs")
        val legs = (0 until legsJson.length()).map { li ->
            val steps = legsJson.getJSONObject(li).getJSONArray("steps")
            val points = mutableListOf<GeoPoint>()
            for (si in 0 until steps.length()) {
                val coords = steps.getJSONObject(si).getJSONObject("geometry").getJSONArray("coordinates")
                for (ci in 0 until coords.length()) {
                    val c = coords.getJSONArray(ci)
                    val p = GeoPoint(c.getDouble(1), c.getDouble(0))
                    if (points.isEmpty() || points.last() != p) points += p
                }
            }
            points
        }

        return Route(
            legs = legs,
            distanceMeters = route.getDouble("distance"),
            durationSeconds = route.getDouble("duration"),
        )
    }
}
