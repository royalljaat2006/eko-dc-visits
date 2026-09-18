package com.eko.dcvisits.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The durable outbox journal (C3 / ADR-0003 / BUILD_PLAN §15.1): every field
 * action is committed here transactionally BEFORE the UI confirms it, and stays
 * until the server acks. Rows map 1:1 to [com.eko.dcvisits.core.outbox.OutboxOp].
 * Terminal rows (ACKED / QUARANTINED / REJECTED) are kept for the local trail,
 * never deleted client-side.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val opId: String,
    val seq: Long,
    val type: String,
    val payload: String,
    val tier: String,
    val enqueuedAtMs: Long,
    val state: String,
    val attempts: Int,
    val notBeforeMs: Long,
    val inFlightBatchId: String?,
    /** Human-readable summary for the "pending sync" UI (not sent to the server). */
    val label: String = "",
)

/**
 * On-device cache of the DC's currently assigned CSPs. Powers CSP Details and
 * the visit check-in picker fully offline, and is the site table the design-0001
 * dwell matcher reads. Refreshed from GET /dc/csp-details on every connectivity.
 */
@Entity(tableName = "csp_cache")
data class CspCacheEntity(
    @PrimaryKey val cspLocationId: String,
    val code: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Double,
    val coordinateConfidence: String,
    val lastVisitDate: String?,
    val profileJson: String,
    val cachedAtMs: Long,
)
