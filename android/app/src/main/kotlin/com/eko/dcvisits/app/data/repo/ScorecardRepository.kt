package com.eko.dcvisits.app.data.repo

import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.C2Api
import com.eko.dcvisits.app.data.net.ScorecardRowDto
import com.eko.dcvisits.app.data.net.bearer
import com.eko.dcvisits.app.data.net.bodyOrThrow
import com.eko.dcvisits.app.data.session.SessionStore
import com.eko.dcvisits.app.util.Ist

/**
 * dc_score_v1 (design 0002) — transparent points/streaks/badges, parity with
 * managers, never pay-linked. The "My Dashboard" link (spec §3) is the
 * signed-in user's own `dashboard_url`, shown to nobody else.
 */
class ScorecardRepository(
    private val session: SessionStore,
    private val api: C2Api = ApiClient.api,
) {
    suspend fun myCard(): ScorecardRowDto? {
        val s = session.current() ?: return null
        return try {
            api.scorecard(bearer(s.accessToken), Ist.today()).bodyOrThrow()
                .items.firstOrNull { it.dc_user_id == s.user.id }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun myDashboardUrl(): String? = session.current()?.user?.dashboard_url?.takeIf { it.isNotBlank() }
}
