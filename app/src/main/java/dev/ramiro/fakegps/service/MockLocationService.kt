package dev.ramiro.fakegps.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ramiro.fakegps.FakeGpsApp
import dev.ramiro.fakegps.MainActivity
import dev.ramiro.fakegps.R
import dev.ramiro.fakegps.sim.MockLocationPusher
import dev.ramiro.fakegps.sim.SimPlan
import dev.ramiro.fakegps.sim.SimState
import dev.ramiro.fakegps.sim.SimStatus
import dev.ramiro.fakegps.sim.SimulationEngine
import dev.ramiro.fakegps.sim.SimulationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Servicio en primer plano que recorre el plan y publica la ubicación simulada cada segundo.
 * Sigue publicando la última posición al terminar la ruta, hasta que el usuario pulse "Detener".
 */
class MockLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private lateinit var pusher: MockLocationPusher

    override fun onCreate() {
        super.onCreate()
        pusher = MockLocationPusher(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_TOGGLE_PAUSE -> SimulationRepository.togglePause()
            ACTION_STOP -> finish(null)
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(SimulationRepository.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            SimulationRepository.stopped("No se pudo iniciar el servicio: ${e.message}")
            stopSelf()
            return
        }

        val plan = SimulationRepository.plan
        if (plan == null) {
            finish("No hay ruta preparada")
            return
        }

        loopJob?.cancel()
        loopJob = scope.launch { runSimulation(plan) }
    }

    private suspend fun runSimulation(plan: SimPlan) {
        try {
            // Si ya estaba simulando (p. ej. de fija a ruta) los proveedores siguen activos: sin cortes.
            pusher.start()
        } catch (e: SecurityException) {
            finish(MSG_NOT_MOCK_APP)
            return
        } catch (e: Exception) {
            finish("Error al registrar la ubicación simulada: ${e.message}")
            return
        }

        val engine = SimulationEngine(plan)
        val notificationManager = getSystemService(NotificationManager::class.java)
        var last = SystemClock.elapsedRealtime()

        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - last) / 1000.0
            last = now

            val paused = SimulationRepository.paused.value
            val speedMps = SimulationRepository.speedKmh.value / 3.6
            val snap = engine.tick(if (paused) 0.0 else dt, speedMps, SimulationRepository.trafficLevel.value)
            val reportedSpeed = if (paused) 0.0 else snap.speedMps

            try {
                pusher.push(snap.position, reportedSpeed.toFloat(), snap.bearing)
            } catch (e: SecurityException) {
                finish(MSG_NOT_MOCK_APP)
                return
            }

            val state = SimState(
                status = when {
                    plan.isFixed -> SimStatus.FIXED
                    engine.isFinished -> SimStatus.FINISHED
                    paused -> SimStatus.PAUSED
                    else -> SimStatus.RUNNING
                },
                position = snap.position,
                bearing = snap.bearing,
                speedKmh = reportedSpeed * 3.6,
                phaseLabel = if (plan.isFixed) "Ubicación fija" else snap.label,
                isWaiting = snap.isWaiting,
                waitRemainingSec = snap.waitRemainingSec,
                distanceDone = snap.distanceDone,
                distanceTotal = plan.totalDistance,
            )
            SimulationRepository.publish(state)
            notificationManager.notify(NOTIFICATION_ID, buildNotification(state))

            delay(TICK_MS)
        }
    }

    private fun finish(error: String?) {
        scope.launch(Dispatchers.Main) {
            loopJob?.cancelAndJoin()
            pusher.stop()
            SimulationRepository.stopped(error)
            ServiceCompat.stopForeground(this@MockLocationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        runBlocking { loopJob?.cancelAndJoin() }
        scope.cancel()
        pusher.stop()
        if (SimulationRepository.state.value.status.isActive) SimulationRepository.stopped()
        super.onDestroy()
    }

    private fun buildNotification(state: SimState): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val togglePause = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (state.status) {
            SimStatus.PAUSED -> "Simulación en pausa"
            SimStatus.FINISHED -> "Ruta completada · manteniendo posición"
            SimStatus.FIXED -> "Ubicación fija activa"
            else -> "Simulando ruta"
        }
        val text = when {
            state.status == SimStatus.FIXED && state.position != null -> String.format(
                Locale.US, "%.6f, %.6f", state.position.latitude, state.position.longitude,
            )
            state.isWaiting -> "${state.phaseLabel}: ${state.waitRemainingSec} s"
            state.distanceTotal > 0 -> String.format(
                Locale.getDefault(), "%s · %.0f km/h · %.0f%%",
                state.phaseLabel, state.speedKmh, 100 * state.distanceDone / state.distanceTotal,
            )
            else -> "Preparando…"
        }

        val builder = NotificationCompat.Builder(this, FakeGpsApp.CHANNEL_SIMULATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
        if (state.status == SimStatus.RUNNING || state.status == SimStatus.PAUSED) {
            builder.addAction(0, if (state.status == SimStatus.PAUSED) "Reanudar" else "Pausar", togglePause)
        }
        return builder.addAction(0, "Detener", stop).build()
    }

    companion object {
        private const val ACTION_START = "dev.ramiro.fakegps.START"
        private const val ACTION_TOGGLE_PAUSE = "dev.ramiro.fakegps.TOGGLE_PAUSE"
        private const val ACTION_STOP = "dev.ramiro.fakegps.STOP"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 1000L

        const val MSG_NOT_MOCK_APP =
            "Esta app no está seleccionada como app de ubicación simulada. " +
                "Ve a Opciones de desarrollador > Seleccionar app de ubicación simulada y elige \"Fake GPS Rutas\"."

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MockLocationService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MockLocationService::class.java).setAction(ACTION_STOP))
        }
    }
}
