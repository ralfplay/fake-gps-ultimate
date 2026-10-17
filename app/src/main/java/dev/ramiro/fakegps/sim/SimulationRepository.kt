package dev.ramiro.fakegps.sim

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import dev.ramiro.fakegps.model.GeoPoint
import dev.ramiro.fakegps.model.TrafficLevel

enum class SimStatus {
    IDLE, RUNNING, PAUSED, FINISHED, FIXED, ERROR;

    /** Hay una ubicación simulada activa (de ruta o fija). */
    val isActive: Boolean get() = isRouteActive || this == FIXED

    /** Hay una simulación de ruta en curso o terminada (manteniendo el destino). */
    val isRouteActive: Boolean get() = this == RUNNING || this == PAUSED || this == FINISHED
}

data class SimState(
    val status: SimStatus = SimStatus.IDLE,
    val position: GeoPoint? = null,
    val bearing: Float = 0f,
    val speedKmh: Double = 0.0,
    val phaseLabel: String = "",
    val isWaiting: Boolean = false,
    val waitRemainingSec: Int = 0,
    val distanceDone: Double = 0.0,
    val distanceTotal: Double = 0.0,
    val error: String? = null,
)

/** Estado compartido entre la UI y el servicio (mismo proceso). */
object SimulationRepository {
    private val _state = MutableStateFlow(SimState())
    val state: StateFlow<SimState> = _state.asStateFlow()

    /** Se puede cambiar en vivo durante la simulación. */
    val speedKmh = MutableStateFlow(40f)
    val trafficLevel = MutableStateFlow(TrafficLevel.FREE)
    val paused = MutableStateFlow(false)

    @Volatile
    var plan: SimPlan? = null
        private set

    fun prepare(plan: SimPlan, speedKmh: Float) {
        this.plan = plan
        this.speedKmh.value = speedKmh
        paused.value = false
        _state.value = SimState(
            status = if (plan.isFixed) SimStatus.FIXED else SimStatus.RUNNING,
            position = plan.phases.firstOrNull()?.let { firstPoint(it) } ?: plan.endPoint,
            phaseLabel = if (plan.isFixed) "Ubicación fija" else "",
            distanceTotal = plan.totalDistance,
        )
    }

    fun publish(state: SimState) {
        _state.value = state
    }

    fun togglePause() {
        paused.value = !paused.value
        _state.update {
            when (it.status) {
                SimStatus.RUNNING -> it.copy(status = SimStatus.PAUSED)
                SimStatus.PAUSED -> it.copy(status = SimStatus.RUNNING)
                else -> it
            }
        }
    }

    fun stopped(error: String? = null) {
        paused.value = false
        _state.value = if (error != null) SimState(status = SimStatus.ERROR, error = error) else SimState()
    }

    fun clearError() {
        if (_state.value.status == SimStatus.ERROR) _state.value = SimState()
    }

    private fun firstPoint(phase: Phase): GeoPoint = when (phase) {
        is Phase.Wait -> phase.point
        is Phase.Drive -> phase.path.points.first()
    }
}
