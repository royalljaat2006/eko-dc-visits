package com.eko.dcvisits.app.data.net

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The C2 contract surface this app consumes (contracts/c2-api/openapi.yaml v0.8.0).
 * Auth endpoints send no bearer; everything else takes one via the [Authorization]
 * header (ApiClient injects it from the session, but keeping it explicit here
 * makes the "which calls are authed" contract obvious).
 */
interface C2Api {

    @POST("auth/otp/request")
    suspend fun requestOtp(@Body body: OtpRequestBody): Response<Unit>

    @POST("auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyBody): Response<LoginResponse>

    @POST("auth/token/refresh")
    suspend fun refresh(@Body body: RefreshBody): Response<RefreshResponse>

    @GET("dc/csp-details")
    suspend fun cspDetails(@Header("Authorization") bearer: String): Response<CspDetailsResponse>

    @GET("dc/nearest-csp")
    suspend fun nearestCsp(
        @Header("Authorization") bearer: String,
        @Query("lat") lat: Double? = null,
        @Query("lng") lng: Double? = null,
        @Query("accuracy_m") accuracyM: Double? = null,
    ): Response<NearestCspResponse>

    /** A DC's own route (their own id is always within their own scope — C6). */
    @GET("dashboard/route-history")
    suspend fun routeHistory(
        @Header("Authorization") bearer: String,
        @Query("date") date: String,
        @Query("dc_user_id") dcUserId: String,
    ): Response<RouteHistoryResponse>

    @GET("master-data/csp-assignments")
    suspend fun cspAssignments(@Header("Authorization") bearer: String): Response<CspAssignmentsResponse>

    @GET("master-data/locations")
    suspend fun locations(
        @Header("Authorization") bearer: String,
        @Query("limit") limit: Int = 1000,
    ): Response<LocationsResponse>

    @POST("dc/csp-change-requests")
    suspend fun createChangeRequest(
        @Header("Authorization") bearer: String,
        @Body body: CreateChangeRequestBody,
    ): Response<CreateChangeRequestResponse>

    @GET("circle/csp-change-requests")
    suspend fun changeRequestQueue(
        @Header("Authorization") bearer: String,
        @Query("status") status: String? = null,
    ): Response<ChangeRequestQueueResponse>

    @POST("circle/csp-change-requests/decide")
    suspend fun decideChangeRequest(
        @Header("Authorization") bearer: String,
        @Body body: DecideChangeRequestBody,
    ): Response<DecideChangeRequestResponse>

    @GET("dashboard/attendance")
    suspend fun attendance(
        @Header("Authorization") bearer: String,
        @Query("date") date: String,
    ): Response<AttendanceResponse>

    @GET("dashboard/visits")
    suspend fun visits(
        @Header("Authorization") bearer: String,
        @Query("date") date: String,
    ): Response<VisitsResponse>

    @GET("dashboard/scorecard")
    suspend fun scorecard(
        @Header("Authorization") bearer: String,
        @Query("date") date: String,
    ): Response<ScorecardResponse>

    @POST("sync/batches")
    suspend fun submitBatch(
        @Header("Authorization") bearer: String,
        @Body batch: SyncBatchDto,
    ): Response<SyncBatchResponse>
}

@kotlinx.serialization.Serializable
data class ChangeRequestQueueResponse(val items: List<CspChangeRequestDto> = emptyList())
