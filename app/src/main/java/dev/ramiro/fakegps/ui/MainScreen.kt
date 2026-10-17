package dev.ramiro.fakegps.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ramiro.fakegps.model.GeoPoint
import dev.ramiro.fakegps.model.SavedPlace
import dev.ramiro.fakegps.model.SavedRoute
import dev.ramiro.fakegps.model.SimMode
import dev.ramiro.fakegps.model.TrafficLevel
import dev.ramiro.fakegps.model.TravelMode
import dev.ramiro.fakegps.sim.SimStatus
import dev.ramiro.fakegps.ui.theme.AppColors

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sim by vm.sim.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val routeActive = sim.status.isRouteActive
    val fixedActive = sim.status == SimStatus.FIXED
    val fixedMode = ui.mode == SimMode.FIXED
    var follow by rememberSaveable { mutableStateOf(true) }

    var showDefaultStopDialog by remember { mutableStateOf(false) }
    var showSignalsDialog by remember { mutableStateOf(false) }
    var showSaveRouteDialog by remember { mutableStateOf(false) }
    var showSavedRoutes by remember { mutableStateOf(false) }
    var renamingPlace by remember { mutableStateOf<SavedPlace?>(null) }
    var renamingRoute by remember { mutableStateOf<SavedRoute?>(null) }

    // --- Ubicación real (GPS) ---
    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    // Mientras simulamos, el GPS "real" devolvería nuestra propia ubicación falsa.
    val realPosition = rememberRealLocation(enabled = hasPermission && !sim.status.isActive)
    // "Dónde estoy": el punto fijo si está activo; si no, el GPS real.
    val currentLocation = if (fixedActive) sim.position else realPosition
    var cameraRequest by remember { mutableStateOf<CameraRequest?>(null) }
    // Punto bajo el pin rojo central (modo Fija).
    var mapCenter by remember { mutableStateOf<GeoPoint?>(null) }
    var centeredOnce by rememberSaveable { mutableStateOf(false) }

    // Acción pendiente de que el usuario conceda el permiso de ubicación.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermission = hasLocationPermission(context)
        val action = pendingAction
        pendingAction = null
        when {
            hasPermission -> action?.invoke()
            action != null -> Toast.makeText(context, "Se necesita el permiso de ubicación", Toast.LENGTH_LONG).show()
        }
    }
    val withLocationPermission = { action: () -> Unit ->
        if (hasPermission) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(requiredPermissions())
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(requiredPermissions())
    }
    LaunchedEffect(currentLocation, routeActive) {
        if (!routeActive) vm.updateCurrentLocation(currentLocation)
    }
    LaunchedEffect(realPosition) {
        if (realPosition != null && !centeredOnce) {
            centeredOnce = true
            cameraRequest = CameraRequest(realPosition, 17.0)
        }
    }

    // Lugar guardado: te fija ahí al instante y lo centra bajo el pin.
    val goToSavedPlace = { id: Long ->
        ui.savedPlaces.find { it.id == id }?.let { place ->
            cameraRequest = CameraRequest(place.point)
            withLocationPermission { vm.fixAtSavedPlace(id) }
        }
        Unit
    }

    Scaffold(
        topBar = {
            AppTopBar(
                mode = ui.mode,
                modeEnabled = !routeActive,
                onModeChange = vm::setMode,
                defaultStopSeconds = ui.defaultStopSeconds,
                onEditDefaultStop = { showDefaultStopDialog = true },
                signalCount = ui.signals.size,
                onOpenSignals = { showSignalsDialog = true },
                onOpenDevSettings = { openDeveloperSettings(context) },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            RouteMap(
                waypoints = if (fixedMode) emptyList() else ui.waypoints,
                route = if (fixedMode) null else ui.route,
                position = if (sim.status.isActive) sim.position else null,
                realPosition = realPosition,
                cameraRequest = cameraRequest,
                fixedPoint = if (fixedActive) ui.fixedPoint else null,
                savedPlaces = if (fixedMode) ui.savedPlaces else emptyList(),
                signals = if (ui.showSignals) ui.signals else emptyList(),
                editable = fixedMode || !routeActive,
                follow = follow && routeActive,
                // En modo Fija, tocar el mapa lleva ese lugar bajo el pin rojo.
                onTap = { p -> if (fixedMode) cameraRequest = CameraRequest(p) else vm.addWaypoint(p) },
                onWaypointMoved = vm::moveWaypoint,
                onCenterChanged = { mapCenter = it },
                onSavedPlaceClick = goToSavedPlace,
                modifier = Modifier.fillMaxSize(),
            )

            if (fixedMode) {
                // Pin rojo de selección: fijo en el centro, el mapa se mueve debajo. La punta marca el lugar.
                val selectPin = remember { MarkerIcons.pin(context, MarkerIcons.SELECT_PIN_COLOR).asImageBitmap() }
                Image(
                    bitmap = selectPin,
                    contentDescription = "Punto de selección",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset { IntOffset(0, -selectPin.height / 2) },
                )
            }

            // Arriba: tarjeta de estado y, debajo, las barras flotantes (modo de viaje y tráfico).
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                StatusCard(
                    sim = sim,
                    onOpenDevSettings = { openDeveloperSettings(context) },
                    onDismissError = vm::dismissError,
                    onStop = vm::stopSimulation,
                )
                if (!fixedMode) {
                    if (sim.status != SimStatus.IDLE) Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier.align(Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FloatingRail(
                            items = TRAVEL_ITEMS,
                            selected = ui.travelMode,
                            onSelect = vm::setTravelMode,
                            enabled = !routeActive,
                        )
                        FloatingRail(
                            items = TRAFFIC_ITEMS,
                            selected = ui.trafficLevel,
                            onSelect = vm::setTrafficLevel,
                        )
                    }
                }
            }

            // Abajo: botón de ubicación y la hoja del modo actual.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (routeActive) {
                    MapFab(
                        icon = if (follow) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed,
                        description = "Seguir posición simulada",
                        highlighted = follow,
                        onClick = { follow = !follow },
                    )
                } else {
                    MapFab(
                        icon = Icons.Filled.MyLocation,
                        description = "Ir a mi ubicación",
                        onClick = {
                            if (currentLocation != null) {
                                cameraRequest = CameraRequest(currentLocation, 17.0)
                            } else {
                                Toast.makeText(context, "Buscando señal GPS…", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (fixedMode) {
                    FixedPanel(
                        selection = mapCenter,
                        fixedPoint = ui.fixedPoint,
                        active = fixedActive,
                        savedPlaces = ui.savedPlaces,
                        onFix = { mapCenter?.let { p -> withLocationPermission { vm.fixAt(p) } } },
                        onStop = vm::stopSimulation,
                        onSavedPlaceClick = goToSavedPlace,
                        onRenamePlace = { renamingPlace = it },
                        onDeletePlace = vm::deleteSavedPlace,
                    )
                } else {
                    RoutePanel(
                        ui = ui,
                        sim = sim,
                        currentLocationKnown = currentLocation != null,
                        onSpeedChange = vm::setSpeed,
                        onStopSecondsChange = vm::setStopSeconds,
                        onMoveBy = vm::moveWaypointBy,
                        onRemove = vm::removeWaypoint,
                        onClear = vm::clear,
                        onStart = { withLocationPermission(vm::startRoute) },
                        onPauseResume = vm::togglePause,
                        onStop = vm::stopSimulation,
                        onSaveRoute = { showSaveRouteDialog = true },
                        onOpenSavedRoutes = { showSavedRoutes = true },
                        onOriginChange = vm::setOriginFromCurrentLocation,
                    )
                }
            }
        }
    }

    // --- Diálogos ---

    renamingPlace?.let { place ->
        NameDialog(
            title = "Nombre del lugar",
            initialName = place.name,
            placeholder = "Casa, Trabajo…",
            onConfirm = { name ->
                vm.renameSavedPlace(place.id, name)
                renamingPlace = null
            },
            onDismiss = { renamingPlace = null },
        )
    }

    if (showSaveRouteDialog) {
        val loaded = ui.loadedRoute
        SaveRouteDialog(
            initialName = loaded?.name ?: vm.nextRouteName(),
            loadedRouteName = loaded?.name,
            customStart = !ui.originFromCurrentLocation,
            onSaveNew = { name ->
                vm.saveRouteAsNew(name)
                showSaveRouteDialog = false
                Toast.makeText(context, "Ruta guardada", Toast.LENGTH_SHORT).show()
            },
            onUpdate = { name ->
                vm.updateLoadedRoute(name)
                showSaveRouteDialog = false
                Toast.makeText(context, "Ruta actualizada", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSaveRouteDialog = false },
        )
    }

    if (showSavedRoutes) {
        SavedRoutesDialog(
            routes = ui.savedRoutes,
            loadedRouteId = ui.loadedRouteId,
            onLoad = { id ->
                vm.loadSavedRoute(id)?.let { points ->
                    if (points.isNotEmpty()) cameraRequest = CameraRequest(points.first(), zoom = 15.0, fitPoints = points)
                }
                showSavedRoutes = false
            },
            onRename = { renamingRoute = it },
            onDelete = vm::deleteSavedRoute,
            onDismiss = { showSavedRoutes = false },
        )
    }

    renamingRoute?.let { route ->
        NameDialog(
            title = "Nombre de la ruta",
            initialName = route.name,
            placeholder = "Casa → Trabajo",
            onConfirm = { name ->
                vm.renameSavedRoute(route.id, name)
                renamingRoute = null
            },
            onDismiss = { renamingRoute = null },
        )
    }

    if (showSignalsDialog) {
        SignalsDialog(
            ui = ui,
            onShowChange = vm::setShowSignals,
            onStopChange = vm::setStopAtSignals,
            onSecondsChange = vm::setSignalSeconds,
            onProbabilityChange = vm::setRedProbability,
            onRefresh = { vm.refreshSignals(currentLocation ?: mapCenter) },
            onDismiss = { showSignalsDialog = false },
        )
    }

    if (showDefaultStopDialog) {
        DefaultStopDialog(
            initialSeconds = ui.defaultStopSeconds,
            onConfirm = {
                vm.setDefaultStopSeconds(it)
                showDefaultStopDialog = false
            },
            onDismiss = { showDefaultStopDialog = false },
        )
    }
}

private val TRAVEL_ITEMS = listOf(
    RailItem(TravelMode.CAR, Icons.Filled.DirectionsCar, "Auto"),
    RailItem(TravelMode.BIKE, Icons.AutoMirrored.Filled.DirectionsBike, "Bici"),
    RailItem(TravelMode.FOOT, Icons.AutoMirrored.Filled.DirectionsWalk, "A pie"),
)

private val TRAFFIC_ITEMS = listOf(
    RailItem(TrafficLevel.FREE, Icons.Filled.Traffic, "Libre", AppColors.TrafficFree),
    RailItem(TrafficLevel.MODERATE, Icons.Filled.Traffic, "Medio", AppColors.TrafficModerate),
    RailItem(TrafficLevel.HEAVY, Icons.Filled.Traffic, "Pesado", AppColors.TrafficHeavy),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    mode: SimMode,
    modeEnabled: Boolean,
    onModeChange: (SimMode) -> Unit,
    defaultStopSeconds: Int,
    onEditDefaultStop: () -> Unit,
    signalCount: Int,
    onOpenSignals: () -> Unit,
    onOpenDevSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Fake GPS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            },
            actions = {
                CompactToggle(
                    options = SimMode.entries.map { it to it.label },
                    selected = mode,
                    onSelect = onModeChange,
                    enabled = modeEnabled,
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Ajustes")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                            text = { Text("Parada por defecto · $defaultStopSeconds s") },
                            onClick = {
                                onEditDefaultStop()
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.Traffic, contentDescription = null) },
                            text = { Text("Semáforos · $signalCount") },
                            onClick = {
                                onOpenSignals()
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.DeveloperMode, contentDescription = null) },
                            text = { Text("Opciones de desarrollador") },
                            onClick = {
                                onOpenDevSettings()
                                menuOpen = false
                            },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

/** Botón redondo flotante sobre el mapa. */
@Composable
private fun MapFab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    SmallFloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(icon, contentDescription = description)
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun openDeveloperSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Activa primero las Opciones de desarrollador", Toast.LENGTH_LONG).show()
    }
}
