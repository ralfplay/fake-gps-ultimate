package dev.ramiro.fakegps.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Guarda las rutas configuradas en SharedPreferences como JSON. */
class SavedRoutesStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved_routes", Context.MODE_PRIVATE)

    fun load(): List<SavedRoute> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val pointsJson = o.getJSONArray("points")
            SavedRoute(
                id = o.getLong("id"),
                name = o.getString("name"),
                points = (0 until pointsJson.length()).map { j ->
                    val p = pointsJson.getJSONObject(j)
                    SavedRoutePoint(GeoPoint(p.getDouble("lat"), p.getDouble("lon")), p.getInt("stop"))
                },
                travelMode = TravelMode.entries.find { it.name == o.optString("mode") } ?: TravelMode.CAR,
                speedKmh = o.optDouble("speed", TravelMode.CAR.defaultSpeedKmh.toDouble()).toFloat(),
                customStart = o.optBoolean("customStart", false),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(routes: List<SavedRoute>) {
        val array = JSONArray()
        routes.forEach { r ->
            val points = JSONArray()
            r.points.forEach { p ->
                points.put(
                    JSONObject()
                        .put("lat", p.point.latitude)
                        .put("lon", p.point.longitude)
                        .put("stop", p.stopSeconds),
                )
            }
            array.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("points", points)
                    .put("mode", r.travelMode.name)
                    .put("speed", r.speedKmh.toDouble())
                    .put("customStart", r.customStart),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "routes"
    }
}
