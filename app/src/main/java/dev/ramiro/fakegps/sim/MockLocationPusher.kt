package dev.ramiro.fakegps.sim

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.LocationServices
import dev.ramiro.fakegps.model.GeoPoint

/**
 * Inyecta ubicaciones falsas en el sistema.
 *
 * - LocationManager: reemplaza los proveedores "gps" y "network" con proveedores de prueba.
 * - FusedLocationProvider (Google Play Services): lo pone en "mock mode", que es lo que usan
 *   la mayoría de apps (Maps, Uber, etc.).
 *
 * Requiere que esta app esté seleccionada en
 * Opciones de desarrollador > Seleccionar app de ubicación simulada; si no, lanza SecurityException.
 */
class MockLocationPusher(context: Context) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val activeProviders = mutableListOf<String>()

    @Volatile
    private var fusedRequested = false

    @Volatile
    private var fusedReady = false

    @Throws(SecurityException::class)
    fun start() {
        if (activeProviders.isNotEmpty()) return
        for (provider in PROVIDERS) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {
                // No existía o no tenemos permiso todavía; lo confirma addTestProvider.
            }
            try {
                addTestProvider(provider)
                locationManager.setTestProviderEnabled(provider, true)
                activeProviders += provider
            } catch (e: SecurityException) {
                stop()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo registrar el proveedor $provider", e)
            }
        }
        check(activeProviders.isNotEmpty()) { "No se pudo registrar ningún proveedor de ubicación" }

        startFused()
    }

    @SuppressLint("MissingPermission")
    private fun startFused() {
        try {
            fusedRequested = true
            fused.setMockMode(true)
                .addOnSuccessListener { fusedReady = true }
                .addOnFailureListener { Log.w(TAG, "Fused mock mode no disponible", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Fused mock mode no disponible", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun push(point: GeoPoint, speedMps: Float, bearing: Float) {
        for (provider in activeProviders) {
            try {
                locationManager.setTestProviderLocation(provider, buildLocation(provider, point, speedMps, bearing))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "setTestProviderLocation($provider) falló", e)
            }
        }
        if (fusedReady) {
            fused.setMockLocation(buildLocation("fused", point, speedMps, bearing))
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        for (provider in activeProviders) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
            } catch (_: Exception) {
            }
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {
            }
        }
        activeProviders.clear()

        if (fusedRequested) {
            try {
                fused.setMockMode(false)
            } catch (_: Exception) {
            }
            fusedRequested = false
            fusedReady = false
        }
    }

    /**
     * Quita proveedores simulados que hayan quedado de una ejecución anterior (p. ej. si la app se cerró
     * a la fuerza mientras simulaba). Si no, el GPS del teléfono se queda "congelado" en la última posición falsa.
     */
    @SuppressLint("MissingPermission")
    fun clearStaleProviders() {
        for (provider in PROVIDERS) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {
                // No había proveedor simulado o no somos la app de ubicación simulada: nada que limpiar.
            }
        }
        try {
            fused.setMockMode(false)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun addTestProvider(provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = ProviderProperties.Builder()
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            locationManager.addTestProvider(provider, props)
        } else {
            locationManager.addTestProvider(
                provider, false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE,
            )
        }
    }

    private fun buildLocation(provider: String, point: GeoPoint, speedMps: Float, bearing: Float) =
        Location(provider).apply {
            latitude = point.latitude
            longitude = point.longitude
            altitude = 0.0
            accuracy = 4f
            speed = speedMps
            this.bearing = bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            verticalAccuracyMeters = 6f
            speedAccuracyMetersPerSecond = 0.5f
            bearingAccuracyDegrees = 5f
        }

    companion object {
        private const val TAG = "MockLocationPusher"
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
