package dev.ramiro.fakegps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.ramiro.fakegps.sim.MockLocationPusher
import org.maplibre.android.MapLibre

class FakeGpsApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Debe inicializarse antes de crear cualquier MapView.
        MapLibre.getInstance(this)

        // Proceso nuevo = ninguna simulación en curso: limpiar lo que haya quedado de un cierre forzado.
        MockLocationPusher(this).clearStaleProviders()

        val channel = NotificationChannel(
            CHANNEL_SIMULATION,
            "Simulación de ruta",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_SIMULATION = "simulation"
    }
}
