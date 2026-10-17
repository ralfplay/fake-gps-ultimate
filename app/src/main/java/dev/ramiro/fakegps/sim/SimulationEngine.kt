package dev.ramiro.fakegps.sim

import dev.ramiro.fakegps.model.Route
import dev.ramiro.fakegps.model.Waypoint
import dev.ramiro.fakegps.routing.PathGeometry
import dev.ramiro.fakegps.model.GeoPoint
import dev.ramiro.fakegps.model.TrafficLevel
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlin.random.Random

/** Una simulación es una secuencia de fases: esperar en un punto o recorrer un tramo. */
sealed interface Phase {
    val label: String

    data class Wait(val point: GeoPoint, val seconds: Double, override val label: String) : Phase
    data class Drive(val path: PathGeometry, override val label: String) : Phase
}

class SimPlan(
    val phases: List<Phase>,
    val totalDistance: Double,
    val endPoint: GeoPoint,
    val isFixed: Boolean = false,
) {

    companion object {
        /** Distancias posibles al semáforo al detenerse en rojo (posición en la cola de autos). */
        private val QUEUE_OFFSETS_M = listOf(10.0, 50.0, 100.0)

        /** Ubicación fija: sin fases, el motor queda "terminado" y mantiene [point] indefinidamente. */
        fun fixed(point: GeoPoint) = SimPlan(emptyList(), 0.0, point, isFixed = true)

        /**
         * El tiempo de parada de cada waypoint se aplica al llegar a él (el del origen = espera antes de salir).
         *
         * [signalStops] trae, por tramo, la distancia a cada semáforo de la ruta. Al construir el plan se
         * decide al azar cuáles están en rojo ([redProbability] de 0 a 1); en esos se para [signalSeconds].
         */
        fun build(
            route: Route,
            waypoints: List<Waypoint>,
            signalStops: List<List<Double>> = emptyList(),
            signalSeconds: Int = 0,
            redProbability: Float = 1f,
            random: Random = Random.Default,
        ): SimPlan {
            require(route.legs.size == waypoints.size - 1) { "La ruta no corresponde a los puntos" }
            val phases = mutableListOf<Phase>()
            var total = 0.0
            var end = waypoints.last().point

            route.legs.forEachIndexed { i, leg ->
                val points = leg.ifEmpty { listOf(waypoints[i].point, waypoints[i + 1].point) }
                val stop = waypoints[i].stopSeconds
                if (stop > 0) {
                    val label = if (i == 0) "Esperando en origen" else "Parada en P${i + 1}"
                    phases += Phase.Wait(points.first(), stop.toDouble(), label)
                }

                val path = PathGeometry(points)
                val label = "Hacia P${i + 2}"
                val redLights = if (signalSeconds <= 0) {
                    emptyList()
                } else {
                    signalStops.getOrNull(i).orEmpty().filter { random.nextFloat() < redProbability }
                }
                var from = 0.0
                for (at in redLights) {
                    // No siempre eres el primer auto: te detienes a 10, 50 o 100 m del semáforo (sin retroceder).
                    val stopAt = (at - QUEUE_OFFSETS_M.random(random)).coerceAtLeast(from).coerceAtMost(at)
                    val gap = (at - stopAt).roundToInt()
                    phases += Phase.Drive(path.subPath(from, stopAt), label)
                    phases += Phase.Wait(
                        path.positionAt(stopAt).first,
                        signalSeconds.toDouble(),
                        if (gap > 0) "Semáforo en rojo · a $gap m" else "Semáforo en rojo",
                    )
                    from = stopAt
                }
                phases += Phase.Drive(path.subPath(from, path.length), label)

                total += path.length
                end = points.last()
            }
            return SimPlan(phases, total, end)
        }
    }
}

data class SimSnapshot(
    val position: GeoPoint,
    val bearing: Float,
    val speedMps: Double,
    val label: String,
    val isWaiting: Boolean,
    val waitRemainingSec: Int,
    val distanceDone: Double,
)

/**
 * Avanza la simulación según el tiempo transcurrido. No sabe nada de Android: solo calcula posiciones.
 *
 * La velocidad no es constante: el tráfico ([TrafficModel]) la reduce y hay aceleración al arrancar
 * y frenado progresivo antes de cada parada (semáforo, punto o final de la ruta).
 */
class SimulationEngine(private val plan: SimPlan, random: Random = Random.Default) {
    private val traffic = TrafficModel(random)
    private var index = 0
    private var progress = 0.0 // segundos en Wait, metros en Drive
    private var completedDistance = 0.0
    private var bearing = 0f
    private var currentSpeed = 0.0

    val isFinished: Boolean get() = index >= plan.phases.size

    init {
        plan.phases.firstNotNullOfOrNull { (it as? Phase.Drive)?.path?.positionAt(0.0)?.second }
            ?.let { bearing = it }
    }

    /** Avanza [dtSec] segundos con velocidad elegida [speedMps] y el [trafficLevel] actual. */
    fun tick(dtSec: Double, speedMps: Double, trafficLevel: TrafficLevel): SimSnapshot {
        traffic.update(dtSec, trafficLevel)
        var remaining = dtSec
        // Pasos cortos para que la aceleración y el frenado sean suaves aunque el tick sea de 1 s.
        while (remaining > 1e-6 && !isFinished) {
            val step = min(remaining, SUB_STEP_S)
            when (val phase = plan.phases[index]) {
                is Phase.Wait -> {
                    currentSpeed = 0.0
                    val left = phase.seconds - progress
                    if (step < left) {
                        progress += step
                        remaining -= step
                    } else {
                        remaining -= left
                        advance()
                    }
                }
                is Phase.Drive -> {
                    val distLeft = phase.path.length - progress
                    if (distLeft <= 0.0) {
                        completedDistance += phase.path.length
                        advance()
                        continue
                    }
                    val cruise = speedMps * traffic.factor
                    // Frenar a tiempo si después de este tramo hay que detenerse.
                    val brakeCap = if (stopsAfter(index)) {
                        sqrt(2 * DECEL_MPS2 * distLeft).coerceAtLeast(MIN_APPROACH_MPS)
                    } else {
                        Double.MAX_VALUE
                    }
                    val target = min(cruise, brakeCap)
                    currentSpeed = if (currentSpeed < target) {
                        min(target, currentSpeed + ACCEL_MPS2 * step)
                    } else {
                        max(target, currentSpeed - DECEL_MPS2 * step)
                    }

                    if (currentSpeed <= 0.0) {
                        remaining -= step // detenido por el tráfico
                        continue
                    }
                    val move = currentSpeed * step
                    if (move < distLeft) {
                        progress += move
                        remaining -= step
                    } else {
                        remaining -= distLeft / currentSpeed
                        completedDistance += phase.path.length
                        advance()
                    }
                }
            }
        }
        return snapshot()
    }

    /** ¿Después del tramo [i] hay que detenerse (espera o fin de la ruta)? */
    private fun stopsAfter(i: Int): Boolean = plan.phases.getOrNull(i + 1).let { it == null || it is Phase.Wait }

    private fun advance() {
        index++
        progress = 0.0
    }

    private fun snapshot(): SimSnapshot {
        if (isFinished) {
            return SimSnapshot(plan.endPoint, bearing, 0.0, "Ruta completada", false, 0, plan.totalDistance)
        }
        return when (val phase = plan.phases[index]) {
            is Phase.Wait -> SimSnapshot(
                position = phase.point,
                bearing = bearing,
                speedMps = 0.0,
                label = phase.label,
                isWaiting = true,
                waitRemainingSec = ceil(phase.seconds - progress).toInt(),
                distanceDone = completedDistance,
            )
            is Phase.Drive -> {
                val (point, segBearing) = phase.path.positionAt(progress)
                if (segBearing != null) bearing = segBearing
                val label = if (traffic.inJam) "${phase.label} · tráfico detenido" else phase.label
                SimSnapshot(point, bearing, currentSpeed, label, false, 0, completedDistance + progress)
            }
        }
    }

    private companion object {
        const val SUB_STEP_S = 0.25
        const val ACCEL_MPS2 = 2.0
        const val DECEL_MPS2 = 3.0
        /** Velocidad mínima al acercarse a una parada, para llegar sin quedarse "frenando" para siempre. */
        const val MIN_APPROACH_MPS = 1.5
    }
}
