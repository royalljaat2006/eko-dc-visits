package com.eko.dcvisits.app.ui.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eko.dcvisits.app.BuildConfig
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoYellow

private data class InfoSection(val icon: ImageVector, val title: Int, val body: Int)

/** "i" tab: what the app does, in plain words, for the signed-in role. */
@Composable
fun InfoScreen(role: String) {
    val sections = buildList {
        add(InfoSection(Icons.Filled.Info, R.string.info_what_title, R.string.info_what_body))
        if (role == "DC") add(InfoSection(Icons.Filled.Route, R.string.info_dc_title, R.string.info_dc_body))
        else add(InfoSection(Icons.Filled.Groups, R.string.info_admin_title, R.string.info_admin_body))
        add(InfoSection(Icons.Filled.Palette, R.string.info_colours_title, R.string.info_colours_body))
        add(InfoSection(Icons.Filled.Lock, R.string.info_privacy_title, R.string.info_privacy_body))
    }
    ScreenContainer { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.eko_logo), contentDescription = "Eko", modifier = Modifier.width(120.dp))
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        stringResource(R.string.login_since) + " · " + stringResource(R.string.info_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            itemsIndexed(sections) { i, s ->
                Entrance(index = i) { SectionCard(s) }
            }
        }
    }
}

@Composable
private fun SectionCard(s: InfoSection) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 18.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(40.dp).background(EkoYellow.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(s.icon, contentDescription = null, tint = EkoBlue, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(s.title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(s.body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
