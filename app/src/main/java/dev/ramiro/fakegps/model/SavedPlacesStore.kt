package dev.ramiro.fakegps.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Guarda los lugares fijos en SharedPreferences como JSON. */
class SavedPlacesStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved_places", Context.MODE_PRIVATE)

    fun load(): List<SavedPlace> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SavedPlace(
                id = o.getLong("id"),
                name = o.getString("name"),
                point = GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(places: List<SavedPlace>) {
        val array = JSONArray()
        places.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("lat", p.point.latitude)
                    .put("lon", p.point.longitude),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "places"
    }
}
