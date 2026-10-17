package dev.ramiro.fakegps.ui

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.ramiro.fakegps.model.GeoPoint

/**
 * Ubicación real del GPS mientras [enabled] sea true (requiere permiso de ubicación ya concedido).
 *
 * Escucha a la vez el proveedor "fused" de Google y los proveedores GPS/red de Android, y se queda
 * con la ubicación más reciente. Así, si el fused queda atascado en una ubicación simulada vieja,
 * igual se obtiene la real. Ignora ubicaciones simuladas (la nuestra o la de otra app).
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberRealLocation(enabled: Boolean): GeoPoint? {
    val context = LocalContext.current
    var location by remember { mutableStateOf<GeoPoint?>(null) }

    DisposableEffect(enabled) {
        if (!enabled) {
            location = null
            return@DisposableEffect onDispose {}
        }

        var lastFixNanos = 0L
        val accept = { loc: Location? ->
            if (loc != null && !loc.isMockCompat() && loc.elapsedRealtimeNanos >= lastFixNanos) {
                lastFixNanos = loc.elapsedRealtimeNanos
                location = GeoPoint(loc.latitude, loc.longitude)
            }
        }

        // Google Play Services (fused): suele ser el más rápido y preciso.
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) = accept(result.lastLocation)
        }
        fused.lastLocation.addOnSuccessListener { accept(it) }
        fused.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build(),
            fusedCallback,
            Looper.getMainLooper(),
        )

        // Proveedores de Android directamente, como respaldo.
        val locationManager = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { accept(it) }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        providers.forEach { provider ->
            runCatching {
                accept(locationManager.getLastKnownLocation(provider))
                locationManager.requestLocationUpdates(provider, 2000L, 0f, listener, Looper.getMainLooper())
            }
        }

        onDispose {
            fused.removeLocationUpdates(fusedCallback)
            locationManager.removeUpdates(listener)
        }
    }
    return location
}

private fun Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock
    else @Suppress("DEPRECATION") isFromMockProvider
