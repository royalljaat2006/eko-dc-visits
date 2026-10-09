package com.eko.dcvisits.app.data.repo

import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.C2Api
import com.eko.dcvisits.app.data.net.NearestCspResponse
import com.eko.dcvisits.app.data.net.bearer
import com.eko.dcvisits.app.data.net.bodyOrThrow
import com.eko.dcvisits.app.data.session.SessionStore

/**
 * GET /dc/nearest-csp (C2 v0.12.0) — the DC's own nearest-eligible-CSP
 * recommendation, re-derived live from the DC's current CspAssignments on
 * every call. Deliberately NOT cached: it is a read over data that's already
 * cached elsewhere (CspRepository) plus whatever fix the caller supplies, not
 * evidence in its own right (ADR-0003) — there is nothing to serve offline
 * that would still be true by the time connectivity returns.
 */
class NearestCspRepository(
    private val session: SessionStore,
    private val api: C2Api = ApiClient.api,
) {
    suspend fun fetch(lat: Double? = null, lng: Double? = null, accuracyM: Double? = null): NearestCspResponse {
        val s = session.current() ?: error("Not signed in")
        return api.nearestCsp(bearer(s.accessToken), lat, lng, accuracyM).bodyOrThrow()
    }
}
