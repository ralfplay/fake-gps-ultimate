package dev.ramiro.fakegps.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ramiro.fakegps.model.GeoPoint
import dev.ramiro.fakegps.model.Route
import dev.ramiro.fakegps.model.SavedPlace
import dev.ramiro.fakegps.model.SavedPlacesStore
import dev.ramiro.fakegps.model.SavedRoute
import dev.ramiro.fakegps.model.SavedRoutePoint
import dev.ramiro.fakegps.model.SavedRoutesStore
import dev.ramiro.fakegps.model.SimMode
import dev.ramiro.fakegps.model.TrafficLevel
import dev.ramiro.fakegps.model.TravelMode
import dev.ramiro.fakegps.model.Waypoint
import dev.ramiro.fakegps.routing.GeoUtils
import dev.ramiro.fakegps.routing.OsrmClient
import dev.ramiro.fakegps.routing.SignalArea
import dev.ramiro.fakegps.routing.SignalMatcher
import dev.ramiro.fakegps.routing.TrafficSignalRepository
import dev.ramiro.fakegps.service.MockLocationService
import dev.ramiro.fakegps.sim.SimPlan
import dev.ramiro.fakegps.sim.SimState
import dev.ramiro.fakegps.sim.SimulationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class UiState(
    val mode: SimMode = SimMode.ROUTE,
    val waypoints: List<Waypoint> = emptyList(),
    val travelMode: TravelMode = TravelMode.CAR,
    val speedKmh: Float = TravelMode.CAR.defaultSpeedKmh,
    val trafficLevel: TrafficLevel = TrafficLevel.FREE,
    val route: Route? = null,
    val routeLoading: Boolean = false,
    val routeError: String? = null,
    val fixedPoint: GeoPoint? = null,
    val savedPlaces: List<SavedPlace> = emptyList(),
    val savedRoutes: List<SavedRoute> = emptyList(),
    /** Ruta guardada que se cargó por última vez (para ofrecer "Actualizar" al guardar). */
    val loadedRouteId: Long? = null,
    /** Inicio de la ruta: true = mi ubicación, false = punto elegido en el mapa. */
    val originFromCurrentLocation: Boolean = true,
    val defaultStopSeconds: Int = DEFAULT_STOP_SECONDS,
    // Semáforos
    val signals: List<GeoPoint> = emptyList(),
    val signalsFetchedAt: Long? = null,
    val signalsLoading: Boolean = false,
    val signalsError: String? = null,
    val showSignals: Boolean = true,
    val stopAtSignals: Boolean = true,
    val signalSeconds: Int = DEFAULT_SIGNAL_SECONDS,
    /** Probabilidad (0-100) de que un semáforo esté en rojo al pasar. */
    val redProbability: Int = DEFAULT_RED_PROBABILITY,
    /** Por cada tramo de la ruta, la distancia (m) a cada semáforo que está sobre él. */
    val routeSignals: List<List<Double>> = emptyList(),
) {
    val routeSignalCount: Int get() = routeSignals.sumOf { it.size }

    /** Espera promedio por semáforos (cuenta la probabilidad de rojo). */
    val expectedSignalSeconds: Double
        get() = if (stopAtSignals) routeSignalCount * signalSeconds * redProbability / 100.0 else 0.0

    /** Tiempo estimado: recorrido a la velocidad elegida + paradas (la del destino no cuenta). */
    val estimatedSeconds: Double?
        get() = route?.let { r ->
            r.distanceMeters / (speedKmh / 3.6 * trafficLevel.expectedFactor) + waypoints.dropLast(1).sumOf { it.stopSeconds } + expectedSignalSeconds
        }

    val canStartRoute: Boolean
        get() = route != null && !routeLoading && route.legs.size == waypoints.size - 1

    /** Hay algo que guardar: al menos un punto aparte del origen automático. */
    val canSaveRoute: Boolean
        get() = if (originFromCurrentLocation) waypoints.any { !it.isCurrentLocation } else waypoints.size >= 2

    val loadedRoute: SavedRoute?
        get() = savedRoutes.find { it.id == loadedRouteId }

    /** La ruta en pantalla es exactamente una ruta guardada (sin cambios pendientes). */
    val isRouteSaved: Boolean
        get() {
            val saved = loadedRoute ?: return false
            val current = waypoints.filterNot { it.isCurrentLocation }.map { SavedRoutePoint(it.point, it.stopSeconds) }
            return saved.points == current &&
                saved.customStart == !originFromCurrentLocation &&
                saved.travelMode == travelMode &&
                saved.speedKmh.roundToInt() == speedKmh.roundToInt()
        }

    companion object {
        const val DEFAULT_STOP_SECONDS = 30
        const val DEFAULT_SIGNAL_SECONDS = 30
        const val DEFAULT_RED_PROBABILITY = 50
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val placesStore = SavedPlacesStore(app)
    private val routesStore = SavedRoutesStore(app)
    private val signalRepo = TrafficSignalRepository(app)
    private var signalArea: SignalArea? = signalRepo.loadCached()
    private var lastSignalAttempt = 0L
    private var lastKnownLocation: GeoPoint? = null

    private val _ui = MutableStateFlow(
        UiState(
            savedPlaces = placesStore.load(),
            savedRoutes = routesStore.load(),
            originFromCurrentLocation = prefs.getBoolean(PREF_ORIGIN_FROM_CURRENT, true),
            trafficLevel = TrafficLevel.entries.find { it.name == prefs.getString(PREF_TRAFFIC, null) } ?: TrafficLevel.FREE,
            defaultStopSeconds = prefs.getInt(PREF_DEFAULT_STOP, UiState.DEFAULT_STOP_SECONDS),
            signals = signalArea?.points.orEmpty(),
            signalsFetchedAt = signalArea?.fetchedAt,
            showSignals = prefs.getBoolean(PREF_SHOW_SIGNALS, true),
            stopAtSignals = prefs.getBoolean(PREF_STOP_AT_SIGNALS, true),
            signalSeconds = prefs.getInt(PREF_SIGNAL_SECONDS, UiState.DEFAULT_SIGNAL_SECONDS),
            redProbability = prefs.getInt(PREF_RED_PROBABILITY, UiState.DEFAULT_RED_PROBABILITY),
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val sim: StateFlow<SimState> = SimulationRepository.state

    private var nextId = 1L

    init {
        SimulationRepository.trafficLevel.value = _ui.value.trafficLevel
    }
    private var routeJob: Job? = null

    // --- Ajustes ---

    fun setMode(mode: SimMode) {
        if (sim.value.status.isRouteActive) return
        _ui.update { it.copy(mode = mode) }
    }

    fun setDefaultStopSeconds(seconds: Int) {
        val value = seconds.coerceIn(0, 3600)
        prefs.edit().putInt(PREF_DEFAULT_STOP, value).apply()
        _ui.update { it.copy(defaultStopSeconds = value) }
    }

    // --- Semáforos ---

    fun setShowSignals(show: Boolean) {
        prefs.edit().putBoolean(PREF_SHOW_SIGNALS, show).apply()
        _ui.update { it.copy(showSignals = show) }
    }

    fun setStopAtSignals(stop: Boolean) {
        prefs.edit().putBoolean(PREF_STOP_AT_SIGNALS, stop).apply()
        _ui.update { it.copy(stopAtSignals = stop) }
    }

    fun setSignalSeconds(seconds: Int) {
        val value = seconds.coerceIn(0, 600)
        prefs.edit().putInt(PREF_SIGNAL_SECONDS, value).apply()
        _ui.update { it.copy(signalSeconds = value) }
    }

    fun setRedProbability(percent: Int) {
        val value = percent.coerceIn(0, 100)
        prefs.edit().putInt(PREF_RED_PROBABILITY, value).apply()
        _ui.update { it.copy(redProbability = value) }
    }

    /** Vuelve a descargar los semáforos de la ciudad alrededor de [center] (o de la última ubicación conocida). */
    fun refreshSignals(center: GeoPoint?) {
        val point = center ?: lastKnownLocation ?: return
        if (_ui.value.signalsLoading) return
        lastSignalAttempt = System.currentTimeMillis()
        _ui.update { it.copy(signalsLoading = true, signalsError = null) }
        viewModelScope.launch {
            try {
                val area = signalRepo.download(point)
                signalArea = area
                _ui.update { it.copy(signals = area.points, signalsFetchedAt = area.fetchedAt, signalsLoading = false) }
                recomputeRouteSignals()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(signalsLoading = false, signalsError = e.message ?: "Error de red") }
            }
        }
    }

    /** Descarga automática si no hay semáforos de la zona donde estás (sin insistir si falla). */
    private fun ensureSignalsFor(point: GeoPoint) {
        lastKnownLocation = point
        if (signalArea?.covers(point) == true) return
        if (System.currentTimeMillis() - lastSignalAttempt < SIGNAL_RETRY_MS) return
        refreshSignals(point)
    }

    private fun recomputeRouteSignals() {
        val s = _ui.value
        val legs = s.route?.legs.orEmpty()
        val matched = if (s.signals.isEmpty()) legs.map { emptyList() } else legs.map { SignalMatcher.signalsOnLeg(it, s.signals) }
        _ui.update { it.copy(routeSignals = matched) }
    }

    // --- Inicio de la ruta ---

    /**
     * true: el inicio es "mi ubicación" (sigue al GPS o al punto fijo).
     * false: el inicio lo elige el usuario en el mapa (primer punto que toca, se puede arrastrar).
     */
    fun setOriginFromCurrentLocation(fromCurrent: Boolean) {
        if (sim.value.status.isRouteActive || fromCurrent == _ui.value.originFromCurrentLocation) return
        setOriginPref(fromCurrent)
        val waypoints = _ui.value.waypoints
        if (fromCurrent) {
            // El inicio elegido se reemplaza por tu ubicación.
            _ui.update { it.copy(waypoints = waypoints.drop(1)) }
            lastKnownLocation?.let(::updateCurrentLocation) ?: requestRoute()
        } else {
            // Si solo estaba tu ubicación, se quita para que toques el inicio; si había más puntos,
            // tu ubicación queda como un inicio normal que puedes arrastrar.
            val updated = if (waypoints.size <= 1) emptyList() else waypoints.map { it.copy(isCurrentLocation = false) }
            _ui.update { it.copy(waypoints = updated) }
            requestRoute()
        }
    }

    private fun setOriginPref(fromCurrent: Boolean) {
        prefs.edit().putBoolean(PREF_ORIGIN_FROM_CURRENT, fromCurrent).apply()
        _ui.update { it.copy(originFromCurrentLocation = fromCurrent) }
    }

    /**
     * Con inicio "mi ubicación", el primer punto es la ubicación actual (GPS real, o el punto fijo si está
     * activo). Se actualiza si te mueves más de [ORIGIN_UPDATE_METERS] para no recalcular la ruta a cada rato.
     */
    fun updateCurrentLocation(point: GeoPoint?) {
        if (point == null) return
        ensureSignalsFor(point)
        if (sim.value.status.isRouteActive || !_ui.value.originFromCurrentLocation) return
        val waypoints = _ui.value.waypoints
        val first = waypoints.firstOrNull()
        val updated = when {
            first == null -> listOf(currentLocationWaypoint(point))
            first.isCurrentLocation ->
                if (GeoUtils.distance(first.point, point) < ORIGIN_UPDATE_METERS) return
                else listOf(first.copy(point = point)) + waypoints.drop(1)
            else -> listOf(currentLocationWaypoint(point)) + waypoints
        }
        _ui.update { it.copy(waypoints = updated) }
        requestRoute()
    }

    private fun currentLocationWaypoint(point: GeoPoint) =
        Waypoint(nextId++, point, stopSeconds = 0, isCurrentLocation = true)

    // --- Puntos de la ruta ---

    fun addWaypoint(point: GeoPoint) {
        _ui.update {
            it.copy(waypoints = it.waypoints + Waypoint(nextId++, point, stopSeconds = it.defaultStopSeconds))
        }
        requestRoute()
    }

    fun moveWaypoint(id: Long, point: GeoPoint) {
        _ui.update { s ->
            s.copy(waypoints = s.waypoints.map { if (it.id == id && !it.isCurrentLocation) it.copy(point = point) else it })
        }
        requestRoute()
    }

    fun removeWaypoint(id: Long) {
        _ui.update { s -> s.copy(waypoints = s.waypoints.filterNot { it.id == id && !it.isCurrentLocation }) }
        requestRoute()
    }

    fun moveWaypointBy(id: Long, delta: Int) {
        val list = _ui.value.waypoints.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        if (list[from].isCurrentLocation || list[to].isCurrentLocation) return
        list.add(to, list.removeAt(from))
        _ui.update { it.copy(waypoints = list) }
        requestRoute()
    }

    fun setStopSeconds(id: Long, seconds: Int) {
        _ui.update { s ->
            s.copy(waypoints = s.waypoints.map { if (it.id == id) it.copy(stopSeconds = seconds.coerceIn(0, 3600)) else it })
        }
    }

    /** Borra los puntos agregados; el origen de "mi ubicación" se conserva. */
    fun clear() {
        routeJob?.cancel()
        _ui.update { s ->
            s.copy(
                waypoints = s.waypoints.filter { it.isCurrentLocation },
                loadedRouteId = null,
                route = null,
                routeSignals = emptyList(),
                routeLoading = false,
                routeError = null,
            )
        }
    }

    fun setTravelMode(mode: TravelMode) {
        if (mode == _ui.value.travelMode) return
        _ui.update { it.copy(travelMode = mode) }
        setSpeed(mode.defaultSpeedKmh)
        requestRoute()
    }

    /** Nivel de tráfico; se puede cambiar en plena simulación. */
    fun setTrafficLevel(level: TrafficLevel) {
        prefs.edit().putString(PREF_TRAFFIC, level.name).apply()
        _ui.update { it.copy(trafficLevel = level) }
        SimulationRepository.trafficLevel.value = level
    }

    fun setSpeed(kmh: Float) {
        _ui.update { it.copy(speedKmh = kmh) }
        SimulationRepository.speedKmh.value = kmh
    }

    // --- Ubicación fija ---

    /**
     * Fija la ubicación en [point]; si ya había una ubicación fija activa, te mueve ahí al instante.
     * El lugar se guarda (si no estaba ya) para poder volver con un toque.
     */
    fun fixAt(point: GeoPoint) {
        _ui.update { it.copy(fixedPoint = point) }
        rememberPlace(point)
        SimulationRepository.prepare(SimPlan.fixed(point), _ui.value.speedKmh)
        MockLocationService.start(getApplication())
    }

    fun fixAtSavedPlace(id: Long) {
        _ui.value.savedPlaces.find { it.id == id }?.let { fixAt(it.point) }
    }

    fun renameSavedPlace(id: Long, name: String) {
        val clean = name.trim().ifEmpty { return }
        updatePlaces { list -> list.map { if (it.id == id) it.copy(name = clean) else it } }
    }

    fun deleteSavedPlace(id: Long) {
        updatePlaces { list -> list.filterNot { it.id == id } }
    }

    private fun rememberPlace(point: GeoPoint) {
        val places = _ui.value.savedPlaces
        if (places.any { GeoUtils.distance(it.point, point) < SAME_PLACE_METERS }) return
        val id = (places.maxOfOrNull { it.id } ?: 0L) + 1
        updatePlaces { it + SavedPlace(id, "Lugar $id", point) }
    }

    private fun updatePlaces(transform: (List<SavedPlace>) -> List<SavedPlace>) {
        val updated = transform(_ui.value.savedPlaces)
        _ui.update { it.copy(savedPlaces = updated) }
        placesStore.save(updated)
    }

    // --- Rutas guardadas ---

    /** Guarda los puntos actuales (sin el origen automático) como una ruta nueva. */
    fun saveRouteAsNew(name: String) {
        val route = currentAsSavedRoute(id = (_ui.value.savedRoutes.maxOfOrNull { it.id } ?: 0L) + 1, name) ?: return
        updateRoutes { it + route }
        _ui.update { it.copy(loadedRouteId = route.id) }
    }

    /** Sobrescribe la ruta que se cargó con los puntos actuales. */
    fun updateLoadedRoute(name: String) {
        val id = _ui.value.loadedRouteId ?: return saveRouteAsNew(name)
        val route = currentAsSavedRoute(id, name) ?: return
        updateRoutes { list -> list.map { if (it.id == id) route else it } }
    }

    /**
     * Reemplaza los puntos actuales por los de la ruta guardada. Si la ruta tiene inicio elegido, empieza
     * ahí; si no, el inicio es tu ubicación actual.
     * Devuelve los puntos cargados (para encuadrar el mapa) o null si no se pudo cargar.
     */
    fun loadSavedRoute(id: Long): List<GeoPoint>? {
        if (sim.value.status.isRouteActive) return null
        val saved = _ui.value.savedRoutes.find { it.id == id } ?: return null
        setOriginPref(!saved.customStart)
        val origin = if (saved.customStart) emptyList() else _ui.value.waypoints.filter { it.isCurrentLocation }
        val points = saved.points.map { Waypoint(nextId++, it.point, it.stopSeconds) }
        _ui.update {
            it.copy(
                waypoints = origin + points,
                travelMode = saved.travelMode,
                loadedRouteId = saved.id,
            )
        }
        setSpeed(saved.speedKmh)
        requestRoute()
        // Si el inicio es "mi ubicación" pero aún no estaba puesto, se agrega con la última conocida.
        if (!saved.customStart && origin.isEmpty()) lastKnownLocation?.let(::updateCurrentLocation)
        return _ui.value.waypoints.map { it.point }
    }

    fun renameSavedRoute(id: Long, name: String) {
        val clean = name.trim().ifEmpty { return }
        updateRoutes { list -> list.map { if (it.id == id) it.copy(name = clean) else it } }
    }

    fun deleteSavedRoute(id: Long) {
        updateRoutes { list -> list.filterNot { it.id == id } }
        if (_ui.value.loadedRouteId == id) _ui.update { it.copy(loadedRouteId = null) }
    }

    fun nextRouteName(): String = "Ruta ${(_ui.value.savedRoutes.maxOfOrNull { it.id } ?: 0L) + 1}"

    private fun currentAsSavedRoute(id: Long, name: String): SavedRoute? {
        val s = _ui.value
        val points = s.waypoints.filterNot { it.isCurrentLocation }.map { SavedRoutePoint(it.point, it.stopSeconds) }
        if (points.isEmpty()) return null
        return SavedRoute(
            id = id,
            name = name.trim().ifEmpty { nextRouteName() },
            points = points,
            travelMode = s.travelMode,
            speedKmh = s.speedKmh,
            customStart = !s.originFromCurrentLocation,
        )
    }

    private fun updateRoutes(transform: (List<SavedRoute>) -> List<SavedRoute>) {
        val updated = transform(_ui.value.savedRoutes)
        _ui.update { it.copy(savedRoutes = updated) }
        routesStore.save(updated)
    }

    // --- Simulación ---

    fun startRoute() {
        val s = _ui.value
        if (!s.canStartRoute) return
        val plan = SimPlan.build(
            route = s.route!!,
            waypoints = s.waypoints,
            signalStops = if (s.stopAtSignals) s.routeSignals else emptyList(),
            signalSeconds = s.signalSeconds,
            redProbability = s.redProbability / 100f,
        )
        SimulationRepository.prepare(plan, s.speedKmh)
        MockLocationService.start(getApplication())
    }

    fun togglePause() = SimulationRepository.togglePause()

    fun stopSimulation() = MockLocationService.stop(getApplication())

    fun dismissError() = SimulationRepository.clearError()

    private fun requestRoute() {
        routeJob?.cancel()
        val s = _ui.value
        if (s.waypoints.size < 2) {
            _ui.update { it.copy(route = null, routeLoading = false, routeError = null, routeSignals = emptyList()) }
            return
        }
        _ui.update { it.copy(routeLoading = true, routeError = null) }
        routeJob = viewModelScope.launch {
            delay(500) // espera a que el usuario termine de tocar el mapa
            try {
                val route = OsrmClient.route(s.waypoints.map { it.point }, s.travelMode)
                _ui.update { it.copy(route = route, routeLoading = false) }
                recomputeRouteSignals()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(route = null, routeLoading = false, routeError = e.message ?: "Error de red", routeSignals = emptyList())
                }
            }
        }
    }

    companion object {
        private const val PREF_DEFAULT_STOP = "default_stop_seconds"
        private const val PREF_ORIGIN_FROM_CURRENT = "origin_from_current"
        private const val PREF_TRAFFIC = "traffic_level"
        private const val PREF_SHOW_SIGNALS = "show_signals"
        private const val PREF_STOP_AT_SIGNALS = "stop_at_signals"
        private const val PREF_SIGNAL_SECONDS = "signal_seconds"
        private const val PREF_RED_PROBABILITY = "red_probability"
        private const val SIGNAL_RETRY_MS = 5 * 60 * 1000L
        private const val ORIGIN_UPDATE_METERS = 15.0
        /** Dos lugares a menos de esta distancia se consideran el mismo (no se duplica). */
        private const val SAME_PLACE_METERS = 10.0
    }
}
