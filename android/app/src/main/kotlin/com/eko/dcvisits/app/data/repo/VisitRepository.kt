package com.eko.dcvisits.app.data.repo

import android.content.Context
import android.os.SystemClock
import com.eko.dcvisits.app.data.db.CspCacheEntity
import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.C2Api
import com.eko.dcvisits.app.data.net.VisitDto
import com.eko.dcvisits.app.data.net.bearer
import com.eko.dcvisits.app.data.net.bodyOrThrow
import com.eko.dcvisits.app.data.session.SessionStore
import com.eko.dcvisits.app.data.sync.Fix
import com.eko.dcvisits.app.data.sync.OutboxRepository
import com.eko.dcvisits.app.data.sync.SyncPayloads
import com.eko.dcvisits.app.data.sync.SyncWorker
import com.eko.dcvisits.app.data.sync.Uuidv7
import com.eko.dcvisits.app.util.Ist
import com.eko.dcvisits.core.geo.effectiveRadiusM
import com.eko.dcvisits.core.geo.haversineMeters

/** Result of the client-side advisory geofence check (server re-derives the truth). */
data class GeoAdvisory(val distanceM: Double, val effectiveRadiusM: Double, val inside: Boolean)

class VisitRepository(
    private val appContext: Context,
    private val outbox: OutboxRepository,
    private val session: SessionStore,
    private val api: C2Api = ApiClient.api,
) {
    /** ADR-0004: advisory only. Used to decide whether to prompt for a reason code. */
    fun advisory(csp: CspCacheEntity, fix: Fix): GeoAdvisory {
        val d = haversineMeters(fix.lat, fix.lng, csp.lat, csp.lng)
        val eff = effectiveRadiusM(csp.radiusM, fix.accuracyM)
        return GeoAdvisory(d, eff, d <= eff)
    }

    /**
     * Log a visit. Never blocks: the op is journalled and the UI confirms
     * immediately. `outOfRadiusReason` is required by the spec's UX only when
     * the advisory check is outside — the server never rejects for radius.
     */
    suspend fun checkIn(
        csp: CspCacheEntity,
        fix: Fix,
        outOfRadiusReason: String?,
        remarks: String?,
    ): String {
        val s = session.current() ?: error("Not signed in")
        val payload = SyncPayloads.checkinEvent(
            id = Uuidv7.next(),
            dcUserId = s.user.id,
            deviceId = s.deviceId,
            locationId = csp.cspLocationId,
            fix = fix,
            wallIso = Ist.nowIso(),
            monotonicMs = SystemClock.elapsedRealtime(),
            outOfRadiusReason = outOfRadiusReason,
            remarks = remarks,
        )
        val opId = outbox.enqueue(
            SyncPayloads.OP_VISIT_CHECKIN,
            payload,
            label = "Visit · ${csp.code}",
        )
        SyncWorker.kick(appContext)
        return opId
    }

    /**
     * Close a visit. `trigger` distinguishes a DC-tapped "Check out" from the
     * design-0001 dwell matcher's sustained-exit auto-close. Never blocks;
     * journalled T1 like the check-in it closes.
     */
    suspend fun checkOut(visitId: String, fix: Fix?, trigger: String = "MANUAL"): String {
        val s = session.current() ?: error("Not signed in")
        val payload = SyncPayloads.checkoutEvent(
            id = Uuidv7.next(),
            visitId = visitId,
            dcUserId = s.user.id,
            deviceId = s.deviceId,
            fix = fix,
            wallIso = Ist.nowIso(),
            monotonicMs = SystemClock.elapsedRealtime(),
            trigger = trigger,
        )
        val opId = outbox.enqueue(SyncPayloads.OP_VISIT_CHECKOUT, payload, label = "Check out")
        SyncWorker.kick(appContext)
        return opId
    }

    /** GET /dashboard/visits?date=today — server-scoped to this DC. Null on failure. */
    suspend fun todayVisits(): List<VisitDto>? {
        val s = session.current() ?: return null
        return try {
            api.visits(bearer(s.accessToken), Ist.today()).bodyOrThrow().items
        } catch (_: Exception) {
            null
        }
    }
}
