package dev.ramiro.fakegps.routing

import android.content.Context
import dev.ramiro.fakegps.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Semáforos descargados de una zona (rectángulo) y cuándo se descargaron. */
data class SignalArea(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val fetchedAt: Long,
    val points: List<GeoPoint>,
) {
    /** ¿[p] está dentro de la zona, con un margen para no quedar justo en el borde? */
    fun covers(p: GeoPoint, margin: Double = 0.02): Boolean =
        p.latitude in (south + margin)..(north - margin) && p.longitude in (west + margin)..(east - margin)
}

/**
 * Descarga los semáforos (highway=traffic_signals) de OpenStreetMap con la API gratuita Overpass
 * y los guarda en un archivo, para no depender del servidor cada vez que se abre la app.
 */
class TrafficSignalRepository(context: Context) {
    private val file = File(context.filesDir, "traffic_signals.json")

    fun loadCached(): SignalArea? = try {
        if (!file.exists()) null else parseCache(JSONObject(file.readText()))
    } catch (_: Exception) {
        null
    }

    /** Descarga la zona de ±[HALF_SIZE_DEG] grados alrededor de [center] (≈ 22 × 22 km, una ciudad). */
    suspend fun download(center: GeoPoint): SignalArea = withContext(Dispatchers.IO) {
        val south = center.latitude - HALF_SIZE_DEG
        val north = center.latitude + HALF_SIZE_DEG
        val west = center.longitude - HALF_SIZE_DEG
        val east = center.longitude + HALF_SIZE_DEG
        val query = "[out:json][timeout:60];node[\"highway\"=\"traffic_signals\"]($south,$west,$north,$east);out skel;"

        var lastError: Exception = IOException("Sin servidores")
        for (server in SERVERS) {
            try {
                val points = fetch(server, query)
                val area = SignalArea(south, west, north, east, System.currentTimeMillis(), points)
                save(area)
                return@withContext area
            } catch (e: Exception) {
                lastError = e // servidor saturado o caído: probar el siguiente
            }
        }
        throw IOException("No se pudieron descargar los semáforos (${lastError.message})")
    }

    private fun fetch(server: String, query: String): List<GeoPoint> {
        val conn = (URL(server).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 70_000
            setRequestProperty("User-Agent", "FakeGpsRutas/0.1 (uso personal)")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val elements = json.getJSONArray("elements")
            return (0 until elements.length()).map { i ->
                val e = elements.getJSONObject(i)
                GeoPoint(e.getDouble("lat"), e.getDouble("lon"))
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun save(area: SignalArea) {
        val points = JSONArray()
        area.points.forEach { points.put(JSONArray().put(it.latitude).put(it.longitude)) }
        val json = JSONObject()
            .put("south", area.south)
            .put("west", area.west)
            .put("north", area.north)
            .put("east", area.east)
            .put("fetchedAt", area.fetchedAt)
            .put("points", points)
        file.writeText(json.toString())
    }

    private fun parseCache(json: JSONObject): SignalArea {
        val points = json.getJSONArray("points")
        return SignalArea(
            south = json.getDouble("south"),
            west = json.getDouble("west"),
            north = json.getDouble("north"),
            east = json.getDouble("east"),
            fetchedAt = json.getLong("fetchedAt"),
            points = (0 until points.length()).map { i ->
                val p = points.getJSONArray(i)
                GeoPoint(p.getDouble(0), p.getDouble(1))
            },
        )
    }

    private companion object {
        const val HALF_SIZE_DEG = 0.1
        val SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )
    }
}
