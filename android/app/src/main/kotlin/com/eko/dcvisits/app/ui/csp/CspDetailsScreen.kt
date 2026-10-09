package com.eko.dcvisits.app.ui.csp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.data.db.CspCacheEntity
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GhostGlassButton
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTag
import com.eko.dcvisits.app.ui.components.GlassTextField
import com.eko.dcvisits.app.ui.components.GlowButton
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoCyan
import com.eko.dcvisits.app.ui.theme.EkoGreen
import com.eko.dcvisits.app.util.Ist
import kotlin.math.roundToInt

private enum class CspFilter(val label: String) { ALL("All"), PENDING("Pending today"), VISITED("Visited today") }
private enum class CspSort(val label: String) { DISTANCE("Nearest first"), NAME("Name") }

@Composable
fun CspDetailsScreen(vm: CspDetailsViewModel = viewModel()) {
    val context = LocalContext.current
    val csps by vm.csps.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.start(context) }

    var editing by remember { mutableStateOf<CspCacheEntity?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CspFilter.ALL) }
    var sort by remember { mutableStateOf(CspSort.DISTANCE) }

    val today = remember { Ist.today() }
    val visible = csps
        .filter { c ->
            (query.isBlank() || c.name.contains(query, ignoreCase = true) || c.code.contains(query, ignoreCase = true)) &&
                when (filter) {
                    CspFilter.ALL -> true
                    CspFilter.VISITED -> c.lastVisitDate == today
                    CspFilter.PENDING -> c.lastVisitDate != today
                }
        }
        .let { list ->
            when (sort) {
                CspSort.NAME -> list.sortedBy { it.name }
                CspSort.DISTANCE -> list.sortedBy { vm.distanceMeters(it) ?: Double.MAX_VALUE }
            }
        }

    ScreenContainer { pad ->
     LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Entrance(index = 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("My CSPs (${visible.size}/${csps.size})", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    TextButton(onClick = { vm.refresh(context) }, enabled = !busy) { Text("Refresh", color = EkoBlue) }
                }
            }
        }
        item {
            Entrance(index = 1) {
                GlassTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search by name or CSP code",
                )
            }
        }
        item {
            Entrance(index = 2) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CspFilter.entries.forEach { f ->
                            FilterChip(f.label, selected = filter == f, onClick = { filter = f })
                        }
                    }
                    SortDropdown(sort = sort, onSelect = { sort = it })
                }
            }
        }
        message?.let { msg ->
            item {
                Entrance(index = 3) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            TextButton(onClick = vm::clearMessage) { Text("OK", color = EkoBlue) }
                        }
                    }
                }
            }
        }
        if (csps.isEmpty()) {
            item {
                Text(
                    "No assigned CSPs cached yet. Pull to refresh when you have signal.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (visible.isEmpty()) {
            item {
                Text("No CSPs match this search/filter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(visible, key = { _, it -> it.cspLocationId }) { i, csp ->
            Entrance(index = i + 4) {
                CspCard(
                    csp = csp,
                    distanceM = vm.distanceMeters(csp),
                    profile = vm.profileOf(csp),
                    visitedToday = csp.lastVisitDate == today,
                    onDirections = {
                        val uri = Uri.parse(
                            "https://www.google.com/maps/dir/?api=1&destination=${csp.lat},${csp.lng}",
                        )
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                    onSuggestEdit = { editing = csp },
                )
            }
        }
     }
    }

    editing?.let { csp ->
        SuggestEditDialog(
            csp = csp,
            fields = vm.editableFields,
            busy = busy,
            onDismiss = { editing = null },
            onSubmit = { field, value ->
                vm.proposeEdit(csp.cspLocationId, field, value)
                editing = null
            },
        )
    }
}

@Composable
private fun CspCard(
    csp: CspCacheEntity,
    distanceM: Double?,
    profile: Map<String, String>,
    visitedToday: Boolean,
    onDirections: () -> Unit,
    onSuggestEdit: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTag(if (visitedToday) "Visited today" else "Pending today", tint = if (visitedToday) EkoGreen else EkoAmber)
                if (csp.coordinateConfidence == "UNVERIFIED") GlassTag("Coordinates unverified", tint = EkoAmber)
            }
            Text(csp.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(csp.code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (csp.address.isNotBlank()) Text(csp.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Lat ${"%.5f".format(csp.lat)}, Lng ${"%.5f".format(csp.lng)}  ·  ${csp.coordinateConfidence}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                distanceM?.let { GlassTag(formatDistance(it), tint = EkoCyan) }
                GlassTag("Last visit: ${csp.lastVisitDate ?: "never"}", tint = EkoAmber)
            }

            if (profile.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(if (expanded) "Hide details" else "Show details (${profile.size})", color = EkoCyan)
                }
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        profile.entries.sortedBy { it.key }.forEach { (k, v) ->
                            Text(
                                "${k.replace('_', ' ').replaceFirstChar { c -> c.uppercase() }}: $v",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton(text = "Get Directions", onClick = onDirections)
                GhostGlassButton(text = "Suggest edit", onClick = onSuggestEdit)
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun SortDropdown(sort: CspSort, onSelect: (CspSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("Sort: ${sort.label}", color = EkoBlue)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change sort", tint = EkoBlue)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CspSort.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.label) }, onClick = { onSelect(s); expanded = false })
            }
        }
    }
}

@Composable
private fun SuggestEditDialog(
    csp: CspCacheEntity,
    fields: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (field: String, value: String) -> Unit,
) {
    var field by remember { mutableStateOf(fields.first()) }
    var value by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Suggest an edit — ${csp.code}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "One field at a time. The change stays pending until your Circle Head approves it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Box {
                    OutlinedTextField(
                        value = field,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Field") },
                        trailingIcon = {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose field")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        fields.forEach { f ->
                            DropdownMenuItem(text = { Text(f) }, onClick = { field = f; expanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("New value") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(field, value) }, enabled = value.isNotBlank() && !busy) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatDistance(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m away" else "${"%.1f".format(m / 1000)} km away"
