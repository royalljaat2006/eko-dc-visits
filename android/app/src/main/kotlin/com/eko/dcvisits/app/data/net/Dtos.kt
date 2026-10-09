package com.eko.dcvisits.app.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DTOs hand-transcribed from contracts/c2-api/openapi.yaml + the contracts/c1-entities
 * schemas (v0.8.0). M1 replaces this transcription with OpenAPI codegen (android/README.md).
 * Every field the server may omit is nullable with a default.
 */

@Serializable
data class GeoPointDto(
    val lat: Double,
    val lng: Double,
    val accuracy_m: Double? = null,
    val provider: String? = null,
    val is_mock: Boolean? = null,
)

@Serializable
data class UserDto(
    val id: String,
    val tenant_id: String,
    val name: String,
    val phone: String,
    val employee_code: String? = null,
    val role: String,
    val scope_location_id: String? = null,
    val status: String,
    /** Spec §3: per-user "My Dashboard" link — shown only to the logged-in user. */
    val dashboard_url: String? = null,
    val home_lat: Double? = null,
    val home_lng: Double? = null,
)

// ---- auth -----------------------------------------------------------------

@Serializable
data class OtpRequestBody(val phone: String)

@Serializable
data class HardwareDto(
    val manufacturer: String,
    val model: String,
    val os_version: String,
)

@Serializable
data class DeviceDto(val hardware: HardwareDto, val public_key: String? = null)

@Serializable
data class OtpVerifyBody(val phone: String, val otp: String, val name: String? = null, val device: DeviceDto)

@Serializable
data class LoginResponse(
    val access_token: String,
    val refresh_token: String,
    val device_id: String,
    val user: UserDto,
)

@Serializable
data class RefreshBody(val refresh_token: String)

@Serializable
data class RefreshResponse(val access_token: String, val refresh_token: String)

// ---- master data --------------------------------------------------------

@Serializable
data class CspDetailDto(
    val csp_location_id: String,
    val code: String,
    val name: String,
    val address: String = "",
    val lat: Double,
    val lng: Double,
    val coordinate_confidence: String = "UNVERIFIED",
    val last_visit_date: String? = null,
    val csp_profile: Map<String, String> = emptyMap(),
)

@Serializable
data class CspDetailsResponse(val items: List<CspDetailDto> = emptyList())

// ---- DC live tracking + smart CSP navigation (v0.12.0) -------------------

@Serializable
data class NearestCspItemDto(
    val csp_location_id: String,
    val code: String,
    val name: String,
    val address: String = "",
    val lat: Double,
    val lng: Double,
    val coordinate_confidence: String = "UNVERIFIED",
    val last_visit_date: String? = null,
    /** Straight-line/haversine (ADR-0004); null only when the server had no fix to measure from. */
    val distance_m: Double? = null,
    val distance_basis: String? = null,
)

@Serializable
data class NearestCspResponse(
    val state: String,
    val items: List<NearestCspItemDto> = emptyList(),
)

@Serializable
data class RouteHistoryPointDto(
    val lat: Double,
    val lng: Double,
    val t: String,
    val accuracy_m: Double? = null,
    val is_mock: Boolean? = null,
)

@Serializable
data class RouteHistoryResponse(
    val dc_user_id: String,
    val date: String,
    val points: List<RouteHistoryPointDto> = emptyList(),
)

@Serializable
data class CspAssignmentDto(
    val id: String,
    val tenant_id: String,
    val circle_id: String,
    val csp_location_id: String,
    val dc_user_id: String,
    val assigned_by_user_id: String,
    val reason: String,
    val valid_from: String,
    val valid_to: String? = null,
    val updated_at: String,
)

@Serializable
data class CspAssignmentsResponse(val items: List<CspAssignmentDto> = emptyList())

@Serializable
data class LocationDto(
    val id: String,
    val tenant_id: String,
    val bank_id: String? = null,
    val type: String,
    val parent_id: String? = null,
    val name: String,
    val code: String,
    val address: String? = null,
    val state: String = "",
    val district: String = "",
    val pin_code: String? = null,
    val coordinates: GeoPointDto,
    val radius_m: Double = 150.0,
    val coordinate_confidence: String = "UNVERIFIED",
    val status: String? = null,
    val updated_at: String = "",
)

@Serializable
data class LocationsResponse(
    val items: List<LocationDto> = emptyList(),
    val next_cursor: String? = null,
)

// ---- change requests --------------------------------------------------

@Serializable
data class ChangeValueDto(
    val old: kotlinx.serialization.json.JsonElement? = null,
    val new: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class CspChangeRequestDto(
    val id: String,
    val csp_location_id: String,
    val requested_by_user_id: String,
    val changes: Map<String, ChangeValueDto> = emptyMap(),
    val status: String,
    val rejection_reason: String? = null,
    val created_at: String,
    val csp_code: String? = null,
    val csp_name: String? = null,
    val requested_by_name: String? = null,
)

@Serializable
data class CreateChangeRequestBody(
    val csp_location_id: String,
    val changes: JsonObject,
)

@Serializable
data class CreateChangeRequestResponse(val request: CspChangeRequestDto)

@Serializable
data class DecideChangeRequestBody(
    val id: String,
    val decision: String, // APPROVED | REJECTED
    val rejection_reason: String? = null,
)

@Serializable
data class DecideChangeRequestResponse(val request: CspChangeRequestDto)

// ---- dashboards -------------------------------------------------------

@Serializable
data class AttendanceRowDto(
    val dc_user_id: String,
    val dc_name: String,
    val status: String,
    val started_at: String? = null,
    val ended_at: String? = null,
    val auto_closed: Boolean = false,
    val hours_worked: Double? = null,
    val km_today: Double = 0.0,
)

@Serializable
data class AttendanceResponse(val items: List<AttendanceRowDto> = emptyList())

@Serializable
data class VisitDto(
    val id: String,
    val dc_user_id: String,
    val dc_name: String? = null,
    val location_id: String,
    val location_name: String? = null,
    val location_code: String? = null,
    val planned: Boolean = false,
    val checkin: VisitCheckinDto,
    val distance_from_master_m: Double? = null,
    val geofence_result: String,
    val out_of_radius_reason: String? = null,
    val sync_state: String,
    /** v0.10.0 — derived at read time from the earliest visit.checkout for this visit. */
    val photo_count: Int = 0,
    val checked_out_at: String? = null,
    val duration_minutes: Int? = null,
)

@Serializable
data class VisitCheckinDto(
    val occurred_at: String,
    val server_received_at: String? = null,
    val fix: GeoPointDto,
)

@Serializable
data class VisitsResponse(val items: List<VisitDto> = emptyList())

@Serializable
data class ScorecardRowDto(
    val dc_user_id: String,
    val dc_name: String,
    val points: Int,
    val visits_done: Int,
    val geo_verified_visits: Int,
    val on_time_start: Boolean,
    val started_at: String? = null,
    val streak_days: Int,
    val badges: List<String> = emptyList(),
)

@Serializable
data class ScorecardResponse(
    val formula_version: String,
    val items: List<ScorecardRowDto> = emptyList(),
)

// ---- sync (C3) ------------------------------------------------------

@Serializable
data class SyncOpDto(
    val op_id: String,
    val seq: Long,
    val type: String,
    val payload: JsonObject,
)

@Serializable
data class QueueDepthDto(val t1: Int = 0, val t2: Int = 0, val t3: Int = 0, val t4: Int = 0)

@Serializable
data class HealthDto(
    val battery_pct: Int? = null,
    val network: String? = null,
    val storage_free_mb: Int? = null,
)

@Serializable
data class SyncBatchDto(
    val batch_id: String,
    val device_id: String,
    val seq_from: Long,
    val seq_to: Long,
    val client_time: String,
    val app_version: String,
    val contract_version: String,
    val queue_depth_by_tier: QueueDepthDto? = null,
    val oldest_unsynced_age_s: Long? = null,
    val health: HealthDto? = null,
    val ops: List<SyncOpDto>,
    /** base64 device-Keystore ECDSA signature over the canonical envelope (C3 §2). */
    val signature: String? = null,
)

@Serializable
data class OpResultDto(
    val op_id: String,
    val result: String,
    val flags: List<String> = emptyList(),
)

@Serializable
data class SyncBatchResponse(
    val batch_id: String,
    val results: List<OpResultDto> = emptyList(),
)

@Serializable
data class ProblemDto(
    val type: String = "about:blank",
    val title: String = "Error",
    val status: Int = 0,
    val detail: String? = null,
)
