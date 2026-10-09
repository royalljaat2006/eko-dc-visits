package com.eko.dcvisits.app.ui.approvals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GhostGlassButton
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlowButton
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.theme.EkoBlue

@Composable
fun ApprovalsScreen(vm: ApprovalsViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    var rejecting by remember { mutableStateOf<String?>(null) }

    ScreenContainer { pad ->
     LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Entrance(index = 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Approvals (${ui.items.size})", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    TextButton(onClick = vm::load) { Text("Refresh", color = EkoBlue) }
                }
            }
        }
        ui.message?.let { msg ->
            item {
                Entrance(index = 1) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            TextButton(onClick = vm::clearMessage) { Text("OK", color = EkoBlue) }
                        }
                    }
                }
            }
        }
        if (ui.loading) item { CircularProgressIndicator(color = EkoBlue) }
        if (!ui.loading && ui.items.isEmpty()) {
            item { Text("Nothing pending in your circle.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        itemsIndexed(ui.items, key = { _, it -> it.id }) { i, r ->
            Entrance(index = i + 2) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${r.csp_name ?: r.csp_location_id} (${r.csp_code ?: "—"})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Proposed by ${r.requested_by_name ?: "a DC"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(color = Color.Black.copy(alpha = 0.08f))
                        r.changes.forEach { (field, ch) ->
                            val old = ch.old?.toString()?.trim('"') ?: "—"
                            val new = ch.new?.toString()?.trim('"') ?: "—"
                            Text("$field:  $old  →  $new", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlowButton(text = "Approve", onClick = { vm.approve(r.id) })
                            GhostGlassButton(text = "Reject", onClick = { rejecting = r.id })
                        }
                    }
                }
            }
        }
     }
    }

    rejecting?.let { id ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejecting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Reject this change") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (sent to the DC)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.reject(id, reason); rejecting = null }) { Text("Reject") }
            },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text("Cancel") } },
        )
    }
}
