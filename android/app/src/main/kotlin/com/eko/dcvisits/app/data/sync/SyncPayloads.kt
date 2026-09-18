package com.eko.dcvisits.app.data.sync

import com.eko.dcvisits.app.data.net.QueueDepthDto
import com.eko.dcvisits.app.data.net.SyncBatchDto
import com.eko.dcvisits.app.data.net.SyncOpDto
import com.eko.dcvisits.app.util.Ist
import com.eko.dcvisits.core.outbox.Tier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/** A GPS reading, or null when the DC hasn't granted location / no fix yet. */
data class Fix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double?,
    val isMock: Boolean = false,
    val provider: String = "fused",
)

/**
 * Builders for the C3 evidence op payloads. Pure — every time input is a
 * parameter — so the shapes are unit-tested against the C1 schemas without a
 * device. `additionalProperties:false` on those schemas means we send exactly
 * the listed keys and nothing else.
 */
object SyncPayloads {

    const val OP_ATTENDANCE_START = "attendance.start"
    const val OP_ATTENDANCE_END = "attendance.end"
    const val OP_VISIT_CHECKIN = "visit.checkin"
    const val OP_VISIT_CHECKOUT = "visit.checkout"
    const val OP_VISIT_PHOTO = "visit.photo"
    const val OP_TRACK_CHUNK = "track.chunk"

    /** One GPS reading for a [trackChunkEvent] (contracts track-chunk.schema.json). */
    data class TrackPt(
        val lat: Double,
        val lng: Double,
        val tIso: String,
        val accuracyM: Double?,
        val isMock: Boolean,
    )

    private fun timestamps(wallIso: String, monotonicMs: Long): JsonObject = buildJsonObject {
        put("device_wall_time", wallIso)
        put("monotonic_ms", monotonicMs)
    }

    private fun fixObject(fix: Fix): JsonObject = buildJsonObject {
        put("lat", fix.lat)
        put("lng", fix.lng)
        if (fix.accuracyM != null) put("accuracy_m", fix.accuracyM)
        put("provider", fix.provider)
        put("is_mock", fix.isMock)
    }

    /** contracts/c1-entities/attendance-event.schema.json */
    fun attendanceEvent(
        id: String,
        dcUserId: String,
        deviceId: String,
        kind: String, // "START" | "END"
        fix: Fix?,
        wallIso: String,
        monotonicMs: Long,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("dc_user_id", dcUserId)
        put("device_id", deviceId)
        put("kind", kind)
        if (fix != null) put("fix", fixObject(fix))
        put("timestamps", timestamps(wallIso, monotonicMs))
    }

    /** contracts/c1-entities/checkin-event.schema.json */
    fun checkinEvent(
        id: String,
        dcUserId: String,
        deviceId: String,
        locationId: String,
        fix: Fix,
        wallIso: String,
        monotonicMs: Long,
        outOfRadiusReason: String?, // null when inside the effective radius
        remarks: String?,
        trigger: String = "MANUAL",
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("dc_user_id", dcUserId)
        put("device_id", deviceId)
        put("location_id", locationId)
        put("planned_stop_id", null as String?)
        put("fix", fixObject(fix))
        put("timestamps", timestamps(wallIso, monotonicMs))
        if (outOfRadiusReason != null) put("out_of_radius_reason", outOfRadiusReason)
        if (!remarks.isNullOrBlank()) put("remarks", remarks.take(500))
        put("trigger", trigger)
    }

    /** contracts/c1-entities/track-chunk.schema.json — raw duty-session points, T3. */
    fun trackChunkEvent(
        id: String,
        dcUserId: String,
        deviceId: String,
        points: List<TrackPt>,
        wallIso: String,
        monotonicMs: Long,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("dc_user_id", dcUserId)
        put("device_id", deviceId)
        putJsonArray("points") {
            points.forEach { p ->
                add(buildJsonObject {
                    put("lat", p.lat)
                    put("lng", p.lng)
                    put("t", p.tIso)
                    if (p.accuracyM != null) put("accuracy_m", p.accuracyM)
                    put("is_mock", p.isMock)
                })
            }
        }
        put("timestamps", timestamps(wallIso, monotonicMs))
    }

    /** contracts/c1-entities/visit-photo.schema.json — watermarked JPEG inline (M1), T2. */
    fun visitPhotoEvent(
        id: String,
        visitId: String,
        dcUserId: String,
        deviceId: String,
        category: String,
        sha256Hex: String,
        width: Int,
        height: Int,
        bytesB64: String,
        watermark: Map<String, String>,
        sidecarSignature: String?,
        fix: Fix?,
        wallIso: String,
        monotonicMs: Long,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("visit_id", visitId)
        put("dc_user_id", dcUserId)
        put("device_id", deviceId)
        put("category", category)
        put("sha256", sha256Hex)
        put("width", width)
        put("height", height)
        put("bytes_b64", bytesB64)
        put("watermark", buildJsonObject { watermark.forEach { (k, v) -> put(k, v) } })
        if (sidecarSignature != null) put("sidecar_signature", sidecarSignature)
        if (fix != null) put("fix", fixObject(fix))
        put("timestamps", timestamps(wallIso, monotonicMs))
    }

    /** contracts/c1-entities/checkout-event.schema.json — closes a checkin by id, T1. */
    fun checkoutEvent(
        id: String,
        visitId: String,
        dcUserId: String,
        deviceId: String,
        fix: Fix?,
        wallIso: String,
        monotonicMs: Long,
        trigger: String = "MANUAL",
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("visit_id", visitId)
        put("dc_user_id", dcUserId)
        put("device_id", deviceId)
        if (fix != null) put("fix", fixObject(fix))
        put("trigger", trigger)
        put("timestamps", timestamps(wallIso, monotonicMs))
    }

    fun tierFor(opType: String): Tier = when (opType) {
        OP_ATTENDANCE_START, OP_ATTENDANCE_END, OP_VISIT_CHECKIN, OP_VISIT_CHECKOUT -> Tier.T1
        OP_VISIT_PHOTO -> Tier.T2
        OP_TRACK_CHUNK -> Tier.T3
        else -> Tier.T1
    }

    /**
     * Canonical string an envelope's signature covers (C3 §2). Order-fixed and
     * delimiter-escaped so the server can recompute it byte-for-byte.
     */
    fun canonicalEnvelope(b: SyncBatchDto): String = buildString {
        append(b.batch_id).append('\n')
        append(b.device_id).append('\n')
        append(b.seq_from).append('\n')
        append(b.seq_to).append('\n')
        append(b.client_time).append('\n')
        append(b.app_version).append('\n')
        append(b.contract_version).append('\n')
        b.ops.forEach { append(it.seq).append(':').append(it.op_id).append(':').append(it.type).append('\n') }
    }

    /** C3 §2 envelope. `seqFrom/seqTo` come from the batch's ops. */
    fun batch(
        batchId: String,
        deviceId: String,
        ops: List<SyncOpDto>,
        appVersion: String,
        contractVersion: String,
        queueDepth: QueueDepthDto,
        oldestUnsyncedAgeS: Long,
        now: Instant = Instant.now(),
    ): SyncBatchDto = SyncBatchDto(
        batch_id = batchId,
        device_id = deviceId,
        seq_from = ops.minOfOrNull { it.seq } ?: 0,
        seq_to = ops.maxOfOrNull { it.seq } ?: 0,
        client_time = Ist.nowIso(now),
        app_version = appVersion,
        contract_version = contractVersion,
        queue_depth_by_tier = queueDepth,
        oldest_unsynced_age_s = oldestUnsyncedAgeS,
        ops = ops,
    )
}
