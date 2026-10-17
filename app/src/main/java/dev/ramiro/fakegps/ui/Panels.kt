package dev.ramiro.fakegps.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ramiro.fakegps.model.GeoPoint
import dev.ramiro.fakegps.model.SavedPlace
import dev.ramiro.fakegps.model.Waypoint
import dev.ramiro.fakegps.routing.GeoUtils
import dev.ramiro.fakegps.sim.SimState
import dev.ramiro.fakegps.sim.SimStatus
import dev.ramiro.fakegps.ui.theme.AppColors
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Hoja inferior genérica
// ---------------------------------------------------------------------------------------------

/** Contenedor estilo "bottom sheet": esquinas grandes, sombra suave y barrita superior. */
@Composable
internal fun BottomSheetCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp)
                .animateContentSize(),
        ) {
            SheetHandle(Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tarjeta de estado (arriba)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun StatusCard(
    sim: SimState,
    onOpenDevSettings: () -> Unit,
    onDismissError: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        sim.status == SimStatus.ERROR -> Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.errorContainer,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        sim.error ?: "Error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = onDismissError) { Text("Cerrar") }
                    TextButton(onClick = onOpenDevSettings) { Text("Opciones de desarrollador") }
                }
            }
        }

        sim.status == SimStatus.FIXED -> StatusSurface(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(Icons.Filled.PushPin, Color(MarkerIcons.FIXED_PIN_COLOR))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ubicación fija activa", style = MaterialTheme.typography.titleSmall)
                    sim.position?.let {
                        Text(
                            formatCoords(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onStop) { Text("Detener") }
            }
        }

        sim.status.isRouteActive -> StatusSurface(modifier) {
            val waiting = sim.isWaiting
            val jammed = sim.phaseLabel.contains("tráfico detenido")
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(
                    when {
                        waiting -> Icons.Filled.Traffic
                        sim.status == SimStatus.PAUSED -> Icons.Filled.Pause
                        else -> Icons.Filled.DirectionsCar
                    },
                    when {
                        waiting -> AppColors.RedLight
                        jammed -> AppColors.TrafficModerate
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (sim.status) {
                            SimStatus.PAUSED -> "En pausa"
                            SimStatus.FINISHED -> "Ruta completada"
                            else -> if (waiting) "${sim.phaseLabel}: ${sim.waitRemainingSec} s" else "Simulando ruta"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (waiting) AppColors.RedLight else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            sim.status == SimStatus.FINISHED -> "Manteniendo la posición del destino"
                            waiting -> "Esperando para continuar"
                            else -> sim.phaseLabel
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoChip(Icons.Filled.Speed, "${sim.speedKmh.roundToInt()} km/h")
                InfoChip(Icons.Filled.Straighten, "${formatDistance(sim.distanceDone)} / ${formatDistance(sim.distanceTotal)}")
            }
            Spacer(Modifier.height(10.dp))
            val fraction = if (sim.distanceTotal > 0) (sim.distanceDone / sim.distanceTotal).toFloat() else 0f
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                strokeCap = StrokeCap.Round,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
        }
    }
}

@Composable
private fun StatusSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, color: Color) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Modo Fija
// ---------------------------------------------------------------------------------------------

@Composable
internal fun FixedPanel(
    selection: GeoPoint?,
    fixedPoint: GeoPoint?,
    active: Boolean,
    savedPlaces: List<SavedPlace>,
    onFix: () -> Unit,
    onStop: () -> Unit,
    onSavedPlaceClick: (Long) -> Unit,
    onRenamePlace: (SavedPlace) -> Unit,
    onDeletePlace: (Long) -> Unit,
) {
    BottomSheetCard {
        Text("Ubicación fija", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (active) "Mueve el mapa libremente: solo cambia si tocas «Mover aquí»."
            else "Mueve el mapa para colocar el pin rojo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (active && fixedPoint != null) PinCoordsRow(MarkerIcons.FIXED_PIN_COLOR, "Fijado", fixedPoint)
        selection?.let { PinCoordsRow(MarkerIcons.SELECT_PIN_COLOR, "Selección", it) }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active) {
                OutlinedButton(
                    onClick = onStop,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text("Detener") }
                val moved = selection != null && fixedPoint != null && GeoUtils.distance(selection, fixedPoint) > 1.0
                Button(
                    onClick = onFix,
                    enabled = moved,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text("Mover aquí") }
            } else {
                Button(
                    onClick = onFix,
                    enabled = selection != null,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fijar ubicación")
                }
            }
        }

        if (savedPlaces.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "LUGARES GUARDADOS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .heightIn(max = 168.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                savedPlaces.forEach { place ->
                    SavedPlaceRow(
                        place = place,
                        isActive = active && place.point == fixedPoint,
                        onClick = { onSavedPlaceClick(place.id) },
                        onRename = { onRenamePlace(place) },
                        onDelete = { onDeletePlace(place.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedPlaceRow(
    place: SavedPlace,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Place,
            contentDescription = null,
            tint = Color(if (isActive) MarkerIcons.FIXED_PIN_COLOR else MarkerIcons.SAVED_PIN_COLOR),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                place.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isActive) "Activo · ${formatCoords(place.point)}" else formatCoords(place.point),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRename, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "Renombrar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Borrar",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PinCoordsRow(color: Int, label: String, point: GeoPoint) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(color)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(formatCoords(point), style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------------------------
// Modo Ruta
// ---------------------------------------------------------------------------------------------

@Composable
internal fun RoutePanel(
    ui: UiState,
    sim: SimState,
    currentLocationKnown: Boolean,
    onSpeedChange: (Float) -> Unit,
    onStopSecondsChange: (Long, Int) -> Unit,
    onMoveBy: (Long, Int) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onSaveRoute: () -> Unit,
    onOpenSavedRoutes: () -> Unit,
    onOriginChange: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val routeActive = sim.status.isRouteActive

    BottomSheetCard {
        // Encabezado: nombre/resumen + acciones.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable { expanded = !expanded },
            ) {
                Text(
                    ui.loadedRoute?.name ?: "Nueva ruta",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    routeSummary(ui, currentLocationKnown),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.routeError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenSavedRoutes, enabled = !routeActive) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Rutas guardadas")
            }
            IconButton(onClick = onSaveRoute, enabled = ui.canSaveRoute && !routeActive) {
                // Amarilla: la ruta está guardada tal cual. Gris: sin guardar o con cambios.
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (ui.isRouteSaved) "Ruta guardada" else "Guardar ruta",
                    tint = if (ui.isRouteSaved) AppColors.Saved else MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (expanded) "Contraer" else "Expandir",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Chips de resumen: semáforos en la ruta.
        if (ui.route != null && ui.routeSignalCount > 0) {
            Spacer(Modifier.height(6.dp))
            InfoChip(
                Icons.Filled.Traffic,
                if (ui.stopAtSignals) "${ui.routeSignalCount} semáforos en la ruta"
                else "${ui.routeSignalCount} semáforos (sin parar)",
                tint = if (ui.stopAtSignals) AppColors.RedLight else Color.Unspecified,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Inicio",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp),
                    )
                    CompactToggle(
                        options = listOf(true to "Mi ubicación", false to "Elegir en mapa"),
                        selected = ui.originFromCurrentLocation,
                        onSelect = onOriginChange,
                        enabled = !routeActive,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${ui.speedKmh.roundToInt()} km/h",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp),
                    )
                    Slider(
                        value = ui.speedKmh,
                        onValueChange = onSpeedChange,
                        valueRange = 1f..130f,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (ui.waypoints.isNotEmpty()) {
                    Column(
                        Modifier
                            .heightIn(max = 190.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ui.waypoints.forEachIndexed { i, wp ->
                            WaypointRow(
                                index = i,
                                total = ui.waypoints.size,
                                waypoint = wp,
                                canMoveUp = i > 0 && !wp.isCurrentLocation && !ui.waypoints[i - 1].isCurrentLocation,
                                enabled = !routeActive,
                                onStopSecondsChange = { onStopSecondsChange(wp.id, it) },
                                onMoveUp = { onMoveBy(wp.id, -1) },
                                onMoveDown = { onMoveBy(wp.id, 1) },
                                onRemove = { onRemove(wp.id) },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        RouteActions(ui, sim, onClear, onStart, onPauseResume, onStop)
    }
}

@Composable
private fun RouteActions(
    ui: UiState,
    sim: SimState,
    onClear: () -> Unit,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    val buttonModifier = Modifier.height(48.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (sim.status) {
            SimStatus.RUNNING, SimStatus.PAUSED -> {
                val paused = sim.status == SimStatus.PAUSED
                FilledTonalButton(
                    onClick = onPauseResume,
                    shape = MaterialTheme.shapes.small,
                    modifier = buttonModifier.weight(1f),
                ) {
                    Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (paused) "Reanudar" else "Pausar")
                }
                StopButton(onStop, buttonModifier.weight(1f))
            }
            SimStatus.FINISHED -> StopButton(onStop, buttonModifier.weight(1f))
            else -> {
                OutlinedButton(
                    onClick = onClear,
                    enabled = ui.waypoints.any { !it.isCurrentLocation },
                    shape = MaterialTheme.shapes.small,
                    modifier = buttonModifier.weight(0.8f),
                ) { Text("Limpiar") }
                Button(
                    onClick = onStart,
                    enabled = ui.canStartRoute,
                    shape = MaterialTheme.shapes.small,
                    modifier = buttonModifier.weight(1.2f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Iniciar")
                }
            }
        }
    }
}

@Composable
private fun StopButton(onStop: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onStop,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = modifier,
    ) {
        Icon(Icons.Filled.Stop, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Detener")
    }
}

@Composable
private fun WaypointRow(
    index: Int,
    total: Int,
    waypoint: Waypoint,
    canMoveUp: Boolean,
    enabled: Boolean,
    onStopSecondsChange: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val isLast = index == total - 1
    val locked = waypoint.isCurrentLocation
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberBadge(index + 1, Color(MarkerIcons.colorFor(index, total)))
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                locked -> "Mi ubicación"
                index == 0 -> "Inicio"
                isLast -> "Destino"
                else -> "Parada"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )

        if (!isLast) {
            CompactNumberField(
                value = waypoint.stopSeconds,
                onValueChange = onStopSecondsChange,
                suffix = "s",
                enabled = enabled,
                key = waypoint.id,
            )
            Spacer(Modifier.width(4.dp))
        }

        if (locked) {
            // "Mi ubicación" no se mueve ni se borra; se reserva el espacio para alinear las filas.
            Spacer(Modifier.width(96.dp))
        } else {
            IconButton(onClick = onMoveUp, enabled = enabled && canMoveUp, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Subir", tint = iconTint)
            }
            IconButton(onClick = onMoveDown, enabled = enabled && !isLast, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Bajar", tint = iconTint)
            }
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Eliminar",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
