package com.eko.dcvisits.app.data.repo

import com.eko.dcvisits.app.data.db.CspCacheDao
import com.eko.dcvisits.app.data.db.CspCacheEntity
import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.C2Api
import com.eko.dcvisits.app.data.net.CreateChangeRequestBody
import com.eko.dcvisits.app.data.net.CspChangeRequestDto
import com.eko.dcvisits.app.data.net.bearer
import com.eko.dcvisits.app.data.net.bodyOrThrow
import com.eko.dcvisits.app.data.session.SessionStore
import com.eko.dcvisits.core.dwell.CspSite
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The DC's assigned CSPs — cached in Room so CSP Details and the visit-check-in
 * picker work fully offline (spec §3: "pull from this same master source").
 * Coordinates/radius come from GET /master-data/locations; codes, addresses,
 * profile and last-visit date from GET /dc/csp-details.
 */
class CspRepository(
    private val dao: CspCacheDao,
    private val session: SessionStore,
    private val api: C2Api = ApiClient.api,
) {
    val cspFlow: Flow<List<CspCacheEntity>> = dao.observeAll()

    suspend fun byId(id: String): CspCacheEntity? = dao.byId(id)

    suspend fun sites(): List<CspSite> =
        dao.all().map { CspSite(it.cspLocationId, it.lat, it.lng, it.radiusM) }

    /** Pull fresh master data. Silent on network failure — the cache keeps serving. */
    suspend fun refresh() {
        val s = session.current() ?: return
        val details = try {
            api.cspDetails(bearer(s.accessToken)).bodyOrThrow().items
        } catch (_: Exception) {
            return
        }
        val radiusById = try {
            api.locations(bearer(s.accessToken)).bodyOrThrow().items.associate { it.id to it.radius_m }
        } catch (_: Exception) {
            emptyMap()
        }
        val now = System.currentTimeMillis()
        val rows = details.map { d ->
            CspCacheEntity(
                cspLocationId = d.csp_location_id,
                code = d.code,
                name = d.name,
                address = d.address,
                lat = d.lat,
                lng = d.lng,
                radiusM = radiusById[d.csp_location_id] ?: 150.0,
                coordinateConfidence = d.coordinate_confidence,
                lastVisitDate = d.last_visit_date,
                profileJson = ApiClient.json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject { d.csp_profile.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
                ),
                cachedAtMs = now,
            )
        }
        dao.upsertAll(rows)
        dao.deleteMissing(rows.map { it.cspLocationId })
    }

    /** POST /dc/csp-change-requests — one field at a time, pending CH approval (spec §3). */
    suspend fun proposeEdit(cspLocationId: String, field: String, value: String): CspChangeRequestDto {
        val s = session.current() ?: error("Not signed in")
        val body = CreateChangeRequestBody(
            csp_location_id = cspLocationId,
            changes = buildJsonObject { put(field, JsonPrimitive(value)) },
        )
        return api.createChangeRequest(bearer(s.accessToken), body).bodyOrThrow().request
    }
}
