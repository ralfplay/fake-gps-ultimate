package dev.ramiro.fakegps.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ramiro.fakegps.model.SavedRoute
import kotlin.math.roundToInt

@Composable
internal fun NameDialog(
    title: String,
    initialName: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                singleLine = true,
                placeholder = { Text(placeholder) },
                shape = MaterialTheme.shapes.small,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

/** Guardar la ruta actual: como nueva, o actualizando la que se cargó. */
@Composable
internal fun SaveRouteDialog(
    initialName: String,
    loadedRouteName: String?,
    customStart: Boolean,
    onSaveNew: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (loadedRouteName != null) "Guardar cambios" else "Guardar ruta") },
        text = {
            Column {
                Text(
                    "Se guardan los puntos, sus tiempos, el modo y la velocidad. " +
                        if (customStart) "Empezará desde el inicio que elegiste." else "Empezará desde tu ubicación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    singleLine = true,
                    label = { Text("Nombre") },
                    shape = MaterialTheme.shapes.small,
                )
            }
        },
        confirmButton = {
            Row {
                if (loadedRouteName != null) {
                    TextButton(onClick = { onSaveNew(text) }, enabled = text.isNotBlank()) { Text("Guardar nueva") }
                    TextButton(onClick = { onUpdate(text) }, enabled = text.isNotBlank()) { Text("Actualizar") }
                } else {
                    TextButton(onClick = { onSaveNew(text) }, enabled = text.isNotBlank()) { Text("Guardar") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
internal fun SavedRoutesDialog(
    routes: List<SavedRoute>,
    loadedRouteId: Long?,
    onLoad: (Long) -> Unit,
    onRename: (SavedRoute) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Route, contentDescription = null) },
        title = { Text("Rutas guardadas") },
        text = {
            if (routes.isEmpty()) {
                Text(
                    "Todavía no guardaste rutas. Arma una y toca la estrella para guardarla.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    routes.forEachIndexed { i, route ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onLoad(route.id) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    route.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (route.id == loadedRouteId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    routeDescription(route),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                            IconButton(onClick = { onRename(route) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Edit, contentDescription = "Renombrar", modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { onDelete(route.id) }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Borrar",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
    )
}

@Composable
internal fun DefaultStopDialog(initialSeconds: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialSeconds.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parada por defecto") },
        text = {
            Column {
                Text(
                    "Tiempo que se asigna a cada punto nuevo de la ruta.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    suffix = { Text("s") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.small,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toIntOrNull() ?: 0) }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
internal fun SignalsDialog(
    ui: UiState,
    onShowChange: (Boolean) -> Unit,
    onStopChange: (Boolean) -> Unit,
    onSecondsChange: (Int) -> Unit,
    onProbabilityChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var secondsText by remember { mutableStateOf(ui.signalSeconds.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Traffic, contentDescription = null) },
        title = { Text("Semáforos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow("Mostrar en el mapa", ui.showSignals, onShowChange)
                SwitchRow("Detenerse en los de la ruta", ui.stopAtSignals, onStopChange)

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secondsText,
                    onValueChange = { value ->
                        secondsText = value.filter(Char::isDigit).take(3)
                        onSecondsChange(secondsText.toIntOrNull() ?: 0)
                    },
                    enabled = ui.stopAtSignals,
                    singleLine = true,
                    label = { Text("Tiempo en rojo") },
                    suffix = { Text("s") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.small,
                )

                Spacer(Modifier.height(12.dp))
                Text("Probabilidad de rojo: ${ui.redProbability}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = ui.redProbability.toFloat(),
                    onValueChange = { onProbabilityChange(it.roundToInt()) },
                    valueRange = 0f..100f,
                    steps = 9,
                    enabled = ui.stopAtSignals,
                )
                Text(
                    "Al iniciar la ruta se decide al azar cuáles están en rojo, y te detienes a 10, 50 o 100 m " +
                        "del semáforo (como si hubiera autos delante).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        ui.signalsLoading -> "Descargando semáforos de la ciudad…"
                        ui.signalsError != null -> "Error: ${ui.signalsError}"
                        ui.signalsFetchedAt != null ->
                            "${ui.signals.size} semáforos cargados · ${formatDate(ui.signalsFetchedAt)}"
                        else -> "Aún no hay semáforos descargados."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.signalsError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Datos de OpenStreetMap: puede que falten algunos semáforos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !ui.signalsLoading,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(if (ui.signalsLoading) "Descargando…" else "Actualizar semáforos")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
