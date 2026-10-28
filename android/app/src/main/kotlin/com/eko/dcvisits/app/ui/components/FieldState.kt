package com.eko.dcvisits.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoGreen
import com.eko.dcvisits.app.ui.theme.EkoRed
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.eko.dcvisits.app.R

/**
 * One reusable shape for every "what happened + what can I do about it" state
 * (offline, pending sync, GPS unavailable, stale location, permission needed,
 * session expired, no eligible CSP, …). Every call site explains what
 * happened and offers a safe next action — never hides an unsynced record or
 * invites a duplicate submission.
 */
@Composable
fun StateBanner(
    message: String,
    modifier: Modifier = Modifier,
    tint: Color = EkoAmber,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    GlassCard(modifier.fillMaxWidth(), tint = tint, cornerRadius = 14.dp, contentPadding = 14.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top = 2.dp).size(8.dp).glassSurface(shape = CircleShape, tint = tint, solid = true))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = tint) }
            }
        }
    }
}

@Composable
fun OfflineBanner(modifier: Modifier = Modifier) =
    StateBanner(stringResource(R.string.banner_offline), modifier, tint = EkoAmber)

@Composable
fun PendingSyncBanner(count: Int, modifier: Modifier = Modifier) =
    StateBanner(pluralStringResource(R.plurals.banner_pending_sync, count, count), modifier, tint = EkoBlue)

@Composable
fun SyncFailedBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) =
    StateBanner(stringResource(R.string.banner_sync_failed), modifier, tint = EkoRed, actionLabel = stringResource(R.string.action_retry_now), onAction = onRetry)

@Composable
fun GpsUnavailableBanner(modifier: Modifier = Modifier) =
    StateBanner(stringResource(R.string.banner_gps_unavailable), modifier, tint = EkoAmber)

@Composable
fun LocationStaleBanner(modifier: Modifier = Modifier) =
    StateBanner(stringResource(R.string.banner_location_stale), modifier, tint = EkoAmber)

@Composable
fun PermissionRequiredBanner(onGrant: () -> Unit, modifier: Modifier = Modifier) =
    StateBanner(stringResource(R.string.banner_permission_required), modifier, tint = EkoAmber, actionLabel = stringResource(R.string.action_grant), onAction = onGrant)

@Composable
fun SessionExpiredBanner(onSignIn: () -> Unit, modifier: Modifier = Modifier) =
    StateBanner(stringResource(R.string.banner_session_expired), modifier, tint = EkoRed, actionLabel = stringResource(R.string.action_sign_in), onAction = onSignIn)

@Composable
fun NoEligibleCspBanner(modifier: Modifier = Modifier) =
    StateBanner(stringResource(R.string.banner_no_eligible_csp), modifier
, tint = EkoGreen)

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = EkoBlue)
    }
}
