package com.eko.dcvisits.app.data.repo

import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.C2Api
import com.eko.dcvisits.app.data.net.CspChangeRequestDto
import com.eko.dcvisits.app.data.net.DecideChangeRequestBody
import com.eko.dcvisits.app.data.net.bearer
import com.eko.dcvisits.app.data.net.bodyOrThrow
import com.eko.dcvisits.app.data.session.SessionStore

/**
 * Circle Head approval queue for DC-proposed CSP edits (spec §4). Server scopes
 * the list to the head's own circle; approving applies the edit to the CSP
 * master, rejecting sends a reason back to the DC.
 */
class ApprovalsRepository(
    private val session: SessionStore,
    private val api: C2Api = ApiClient.api,
) {
    suspend fun queue(status: String? = "PENDING"): List<CspChangeRequestDto> {
        val s = session.current() ?: return emptyList()
        return api.changeRequestQueue(bearer(s.accessToken), status).bodyOrThrow().items
    }

    suspend fun decide(id: String, decision: String, rejectionReason: String?): CspChangeRequestDto {
        val s = session.current() ?: error("Not signed in")
        return api.decideChangeRequest(
            bearer(s.accessToken),
            DecideChangeRequestBody(id = id, decision = decision, rejection_reason = rejectionReason),
        ).bodyOrThrow().request
    }
}
