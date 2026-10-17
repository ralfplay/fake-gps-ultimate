package dev.ramiro.fakegps.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ramiro.fakegps.model.GeoPoint
import dev.ramiro.fakegps.model.Route
import dev.ramiro.fakegps.model.SavedPlace
import dev.ramiro.fakegps.model.Waypoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.hypot

/** Estilo "Liberty" de OpenFreeMap: mapa de calles claro, parecido al mapa normal de Google. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Centro inicial del mapa (Lima) mientras no hay ubicación real. */
private val DEFAULT_CENTER = LatLng(-12.0464, -77.0428)

/**
 * Pedido de mover la cámara ([zoom] null = mantener el actual); cada pedido nuevo debe tener un [id] distinto.
 * Si [fitPoints] tiene 2+ puntos, se encuadran todos (dejando espacio para el panel de abajo).
 */
data class CameraRequest(
    val point: GeoPoint,
    val zoom: Double? = null,
    val fitPoints: List<GeoPoint> = emptyList(),
    val id: Long = System.nanoTime(),
)

private data class MapData(
    val waypoints: List<Waypoint>,
    val route: Route?,
    val position: GeoPoint?,
    val realPosition: GeoPoint?,
    val cameraRequest: CameraRequest?,
    val fixedPoint: GeoPoint?,
    val savedPlaces: List<SavedPlace>,
    val signals: List<GeoPoint>,
    val editable: Boolean,
    val follow: Boolean,
)

@Composable
fun RouteMap(
    waypoints: List<Waypoint>,
    route: Route?,
    position: GeoPoint?,
    realPosition: GeoPoint?,
    cameraRequest: CameraRequest?,
    fixedPoint: GeoPoint?,
    savedPlaces: List<SavedPlace>,
    signals: List<GeoPoint>,
    editable: Boolean,
    follow: Boolean,
    onTap: (GeoPoint) -> Unit,
    onWaypointMoved: (Long, GeoPoint) -> Unit,
    onCenterChanged: (GeoPoint) -> Unit,
    onSavedPlaceClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { RouteMapController(context) }
    controller.onTap = onTap
    controller.onWaypointMoved = onWaypointMoved
    controller.onCenterChanged = onCenterChanged
    controller.onSavedPlaceClick = onSavedPlaceClick

    DisposableEffect(lifecycleOwner) {
        val mapView = controller.mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose { controller.mapView.onDestroy() }
    }

    AndroidView(factory = { controller.mapView }, modifier = modifier) {
        controller.render(MapData(waypoints, route, position, realPosition, cameraRequest, fixedPoint, savedPlaces, signals, editable, follow))
    }
}

/**
 * Maneja el MapView de MapLibre: capas de ruta, puntos y ubicaciones, toques y arrastre de puntos.
 * Los datos llegan por [render] y solo se actualizan las fuentes que cambiaron.
 */
@SuppressLint("ClickableViewAccessibility")
private class RouteMapController(private val context: Context) {
    val mapView = MapView(context)
    var onTap: (GeoPoint) -> Unit = {}
    var onWaypointMoved: (Long, GeoPoint) -> Unit = { _, _ -> }
    var onCenterChanged: (GeoPoint) -> Unit = {}
    var onSavedPlaceClick: (Long) -> Unit = {}

    private val density = context.resources.displayMetrics.density
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private val routeSource = GeoJsonSource(SRC_ROUTE)
    private val waypointSource = GeoJsonSource(SRC_WAYPOINTS)
    private val realSource = GeoJsonSource(SRC_REAL)
    private val simSource = GeoJsonSource(SRC_SIM)
    private val fixedSource = GeoJsonSource(SRC_FIXED)
    private val savedSource = GeoJsonSource(SRC_SAVED)
    private val signalSource = GeoJsonSource(SRC_SIGNALS)
    private var renderedSignals: List<GeoPoint>? = null

    /** Último valor puesto en cada fuente de un solo punto, para no reenviar lo mismo en cada cuadro. */
    private val lastPoints = HashMap<GeoJsonSource, GeoPoint?>()
    private var renderedSaved: Pair<List<SavedPlace>, GeoPoint?>? = null

    private var data: MapData? = null
    private var renderedRoute: Route? = null
    private var renderedWaypoints: List<Waypoint>? = null
    private var lastCameraId: Long? = null

    private var draggingId: Long? = null
    private var dragPoint: LatLng? = null

    init {
        mapView.onCreate(null)
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder().target(DEFAULT_CENTER).zoom(14.0).build()
            configureUi(m)
            m.addOnMapClickListener(::handleClick)
            m.addOnMapLongClickListener(::startDrag)
            m.addOnCameraMoveListener(::reportCenter)
            m.addOnCameraIdleListener(::reportCenter)
            reportCenter()
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                addLayers(s)
                style = s
                renderedRoute = null
                renderedWaypoints = null
                renderedSaved = null
                renderedSignals = null
                lastPoints.clear()
                data?.let(::apply)
            }
        }
        mapView.setOnTouchListener { _, ev -> handleDragTouch(ev) }
    }

    /**
     * Informa el punto que está exactamente en el centro de la pantalla (donde apunta el pin rojo).
     * No se usa el "target" de la cámara porque, si la cámara tiene márgenes (padding), el target
     * ya no coincide con el centro visible y el punto fijado quedaría desplazado.
     */
    private fun reportCenter() {
        val m = map ?: return
        if (mapView.width == 0 || mapView.height == 0) return
        val center = m.projection.fromScreenLocation(PointF(mapView.width / 2f, mapView.height / 2f))
        onCenterChanged(GeoPoint(center.latitude, center.longitude))
    }

    /** Lleva [point] al centro exacto de la pantalla, quitando márgenes que hubieran quedado de un encuadre. */
    private fun centerOn(m: MapLibreMap, point: GeoPoint, zoom: Double?, durationMs: Int) {
        val position = CameraPosition.Builder(m.cameraPosition)
            .target(point.toLatLng())
            .apply { if (zoom != null) zoom(zoom) }
            .padding(0.0, 0.0, 0.0, 0.0)
            .build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(position), durationMs)
    }

    fun render(newData: MapData) {
        data = newData
        if (style != null) apply(newData)
    }

    private fun configureUi(m: MapLibreMap) {
        val top = (12 * density).toInt()
        val side = (12 * density).toInt()
        m.uiSettings.apply {
            isLogoEnabled = false
            // Crédito de los datos (obligatorio) y brújula arriba, porque abajo está el panel.
            attributionGravity = Gravity.TOP or Gravity.START
            setAttributionMargins(side, top, 0, 0)
            // Brújula a la izquierda, bajo el crédito: a la derecha van las barras flotantes.
            compassGravity = Gravity.TOP or Gravity.START
            setCompassMargins(side, (52 * density).toInt(), 0, 0)
            isCompassEnabled = true
        }
    }

    private fun addLayers(s: Style) {
        s.addSource(routeSource)
        s.addSource(realSource)
        s.addSource(waypointSource)
        s.addSource(simSource)
        s.addSource(fixedSource)
        s.addSource(savedSource)
        s.addSource(signalSource)
        s.addImage(ICON_SIGNAL, MarkerIcons.trafficLight(context))
        s.addImage(ICON_FIXED, MarkerIcons.pin(context, MarkerIcons.FIXED_PIN_COLOR))
        s.addImage(ICON_SAVED, MarkerIcons.pin(context, MarkerIcons.SAVED_PIN_COLOR))

        s.addLayer(
            LineLayer(LAYER_ROUTE_CASING, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(MarkerIcons.ROUTE_CASING_COLOR),
                PropertyFactory.lineWidth(9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(MarkerIcons.ROUTE_COLOR),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        // Semáforos: solo con zoom de calle, para no llenar el mapa al alejarse.
        s.addLayer(
            SymbolLayer(LAYER_SIGNALS, SRC_SIGNALS).withProperties(
                PropertyFactory.iconImage(ICON_SIGNAL),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            ).apply { minZoom = 14f },
        )
        addPositionLayers(s, "real", SRC_REAL, MarkerIcons.REAL_POSITION_COLOR)
        s.addLayer(
            SymbolLayer(LAYER_WAYPOINTS, SRC_WAYPOINTS).withProperties(
                PropertyFactory.iconImage(Expression.get(PROP_ICON)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            ),
        )
        addPositionLayers(s, "sim", SRC_SIM, MarkerIcons.SIM_POSITION_COLOR)
        // Lugares guardados (azul claro) debajo del pin activo.
        s.addLayer(
            SymbolLayer(LAYER_SAVED, SRC_SAVED).withProperties(
                PropertyFactory.iconImage(ICON_SAVED),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            ),
        )
        // Pin azul de la ubicación fija: la punta (abajo) marca el lugar exacto.
        s.addLayer(
            SymbolLayer(LAYER_FIXED, SRC_FIXED).withProperties(
                PropertyFactory.iconImage(ICON_FIXED),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            ),
        )
    }

    /** Punto con halo semitransparente, como el "punto azul" de los mapas. */
    private fun addPositionLayers(s: Style, prefix: String, sourceId: String, color: Int) {
        s.addLayer(
            CircleLayer("$prefix-halo", sourceId).withProperties(
                PropertyFactory.circleRadius(18f),
                PropertyFactory.circleColor(color),
                PropertyFactory.circleOpacity(0.2f),
            ),
        )
        s.addLayer(
            CircleLayer("$prefix-dot", sourceId).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(color),
                PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }

    private fun apply(d: MapData) {
        val m = map ?: return
        val s = style ?: return

        if (d.route !== renderedRoute) {
            renderedRoute = d.route
            val lines = d.route?.legs.orEmpty().filter { it.size >= 2 }.map { leg ->
                Feature.fromGeometry(LineString.fromLngLats(leg.map { Point.fromLngLat(it.longitude, it.latitude) }))
            }
            routeSource.setGeoJson(FeatureCollection.fromFeatures(lines))
        }
        if (d.waypoints != renderedWaypoints && draggingId == null) {
            renderedWaypoints = d.waypoints
            updateWaypointSource(s, d.waypoints)
        }
        setPoint(realSource, d.realPosition)
        setPoint(simSource, d.position)
        setPoint(fixedSource, d.fixedPoint)
        if (d.signals !== renderedSignals) {
            renderedSignals = d.signals
            val features = d.signals.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
            signalSource.setGeoJson(FeatureCollection.fromFeatures(features))
        }
        if (renderedSaved != (d.savedPlaces to d.fixedPoint)) {
            renderedSaved = d.savedPlaces to d.fixedPoint
            // El lugar activo ya se dibuja con el pin azul fuerte; no lo repetimos.
            val features = d.savedPlaces
                .filter { d.fixedPoint == null || it.point != d.fixedPoint }
                .map { place ->
                    Feature.fromGeometry(Point.fromLngLat(place.point.longitude, place.point.latitude)).apply {
                        addNumberProperty(PROP_PLACE_ID, place.id)
                    }
                }
            savedSource.setGeoJson(FeatureCollection.fromFeatures(features))
        }

        val camera = d.cameraRequest
        if (camera != null && camera.id != lastCameraId) {
            lastCameraId = camera.id
            if (camera.fitPoints.size >= 2) {
                // Encuadre de una ruta: con margen abajo para que no quede tapada por el panel.
                val bounds = LatLngBounds.Builder().includes(camera.fitPoints.map { it.toLatLng() }).build()
                val side = (48 * density).toInt()
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, side, side, side, (360 * density).toInt()), 600)
            } else {
                centerOn(m, camera.point, camera.zoom, 600)
            }
        } else if (d.follow && d.position != null) {
            m.animateCamera(CameraUpdateFactory.newLatLng(d.position.toLatLng()), 900)
        }
    }

    private fun updateWaypointSource(s: Style, waypoints: List<Waypoint>) {
        val features = waypoints.mapIndexed { i, wp ->
            val color = MarkerIcons.colorFor(i, waypoints.size)
            val iconName = "wp-${i + 1}-$color"
            if (s.getImage(iconName) == null) s.addImage(iconName, MarkerIcons.numbered(context, i + 1, color))

            val latLng = if (wp.id == draggingId) dragPoint ?: wp.point.toLatLng() else wp.point.toLatLng()
            Feature.fromGeometry(Point.fromLngLat(latLng.longitude, latLng.latitude)).apply {
                addStringProperty(PROP_ICON, iconName)
            }
        }
        waypointSource.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun setPoint(source: GeoJsonSource, point: GeoPoint?) {
        if (lastPoints.containsKey(source) && lastPoints[source] == point) return
        lastPoints[source] = point
        val features = listOfNotNull(point?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // --- Toques ---

    private fun handleClick(latLng: LatLng): Boolean {
        val d = data ?: return false
        savedPlaceAt(latLng)?.let {
            onSavedPlaceClick(it)
            return true
        }
        if (!d.editable) return false
        if (waypointAt(latLng) != null) return true // tocar un punto no agrega otro encima
        onTap(GeoPoint(latLng.latitude, latLng.longitude))
        return true
    }

    /** Mantener presionado un punto lo "levanta" para arrastrarlo. */
    private fun startDrag(latLng: LatLng): Boolean {
        val d = data ?: return false
        if (!d.editable) return false
        val id = waypointAt(latLng) ?: return false
        // El origen de "mi ubicación" sigue al GPS: no se arrastra.
        if (d.waypoints.any { it.id == id && it.isCurrentLocation }) return false
        draggingId = id
        dragPoint = null
        map?.uiSettings?.isScrollGesturesEnabled = false
        mapView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    /** Mientras se arrastra, sigue al dedo; al soltar confirma la nueva posición. Nunca consume el evento. */
    private fun handleDragTouch(ev: MotionEvent): Boolean {
        val id = draggingId ?: return false
        val m = map ?: return false
        val s = style ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                dragPoint = m.projection.fromScreenLocation(PointF(ev.x, ev.y))
                data?.let { updateWaypointSource(s, it.waypoints) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val target = dragPoint
                draggingId = null
                dragPoint = null
                m.uiSettings.isScrollGesturesEnabled = true
                if (target != null) {
                    onWaypointMoved(id, GeoPoint(target.latitude, target.longitude))
                } else {
                    data?.let { updateWaypointSource(s, it.waypoints) }
                }
            }
        }
        return false
    }

    /** Lugar guardado cuyo pin está bajo el toque. */
    private fun savedPlaceAt(latLng: LatLng): Long? {
        val m = map ?: return null
        val screen = m.projection.toScreenLocation(latLng)
        return m.queryRenderedFeatures(screen, LAYER_SAVED)
            .firstNotNullOfOrNull { it.getNumberProperty(PROP_PLACE_ID)?.toLong() }
    }

    /** Punto más cercano a [latLng] en pantalla, si está a menos de ~24 dp. */
    private fun waypointAt(latLng: LatLng): Long? {
        val m = map ?: return null
        val waypoints = data?.waypoints ?: return null
        val touch = m.projection.toScreenLocation(latLng)
        return waypoints
            .map { wp -> wp.id to m.projection.toScreenLocation(wp.point.toLatLng()) }
            .map { (id, p) -> id to hypot((p.x - touch.x).toDouble(), (p.y - touch.y).toDouble()) }
            .filter { it.second <= 24 * density }
            .minByOrNull { it.second }
            ?.first
    }

    private fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

    companion object {
        private const val SRC_ROUTE = "app-route"
        private const val SRC_WAYPOINTS = "app-waypoints"
        private const val SRC_REAL = "app-real"
        private const val SRC_SIM = "app-sim"
        private const val SRC_FIXED = "app-fixed"
        private const val SRC_SAVED = "app-saved"
        private const val SRC_SIGNALS = "app-signals"
        private const val LAYER_SIGNALS = "app-signals-layer"
        private const val ICON_SIGNAL = "traffic-light"
        private const val LAYER_SAVED = "app-saved-pins"
        private const val ICON_SAVED = "saved-pin"
        private const val PROP_PLACE_ID = "placeId"
        private const val LAYER_FIXED = "app-fixed-pin"
        private const val LAYER_ROUTE_CASING = "app-route-casing"
        private const val LAYER_ROUTE = "app-route-line"
        private const val LAYER_WAYPOINTS = "app-waypoints-layer"
        private const val PROP_ICON = "icon"
        private const val ICON_FIXED = "fixed-pin"
    }
}
