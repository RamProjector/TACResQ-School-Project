package com.tacresq.app.data.remote

import com.google.gson.annotations.SerializedName
import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.IncidentType
import com.tacresq.app.data.model.User
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The Laravel REST contract (see docs/API.md).
 * If your backend uses different routes or field names, change them here and in Models.kt only.
 */
interface TacApi {
    @POST("login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("logout")
    suspend fun logout()

    @GET("me")
    suspend fun me(): User

    @PATCH("me/duty-status")
    suspend fun setDutyStatus(@Body body: DutyStatusRequest): User

    /** Requester: their own requests. Responder/driver: incidents assigned to them. */
    @GET("incidents/mine")
    suspend fun myIncidents(): List<Incident>

    /** Dispatcher: full incident queue, newest first. */
    @GET("incidents")
    suspend fun allIncidents(): List<Incident>

    /** Dispatcher: responders and drivers available for assignment. */
    @GET("units")
    suspend fun units(): List<User>

    @POST("incidents")
    suspend fun createIncident(@Body body: CreateIncidentRequest): Incident

    @PATCH("incidents/{id}/status")
    suspend fun updateIncidentStatus(
        @Path("id") id: Long,
        @Body body: UpdateStatusRequest,
    ): Incident

    /** Dispatcher: assign an available responder or driver to an incident. */
    @POST("incidents/{id}/assign")
    suspend fun assignIncident(
        @Path("id") id: Long,
        @Body body: AssignIncidentRequest,
    ): Incident

    /** Reserved for foreground responder tracking while the assigned incident is en route. */
    @POST("me/locations")
    suspend fun recordLocation(@Body body: ResponderLocationRequest): Long
}

data class LoginRequest(val email: String, val password: String)

data class RegisterRequest(
    val name: String,
    val email: String,
    val phone: String,
    val password: String,
)

data class AuthResponse(val token: String, val user: User)

data class DutyStatusRequest(@SerializedName("duty_status") val dutyStatus: DutyStatus)

data class CreateIncidentRequest(
    val type: IncidentType,
    val description: String?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerializedName("accuracy_m") val accuracyM: Float?,
    @SerializedName("location_notes") val locationNotes: String? = null,
    @SerializedName("location_recorded_at") val locationRecordedAt: String? = null,
)

data class UpdateStatusRequest(val status: IncidentStatus)

data class AssignIncidentRequest(@SerializedName("user_id") val userId: Long)

data class ResponderLocationRequest(
    @SerializedName("incident_id") val incidentId: Long,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("accuracy_m") val accuracyM: Float?,
    @SerializedName("recorded_at") val recordedAt: String,
)
