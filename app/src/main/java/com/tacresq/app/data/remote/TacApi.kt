package com.tacresq.app.data.remote

import com.google.gson.annotations.SerializedName
import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.IncidentType
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.User
import com.tacresq.app.data.model.UserNotification
import com.tacresq.app.data.model.IncidentMessage
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The Laravel REST contract (see docs/API.md).
 * If your backend uses different routes or field names, change them here and in Models.kt only.
 */
/**
 * Notification capability is kept separate from the Laravel REST contract because
 * the current persistent notification RPCs are Supabase-specific.
 */
interface TacNotificationApi {
    suspend fun myNotifications(limit: Int): List<UserNotification>
    suspend fun markNotificationRead(notificationId: Long): Boolean
}

interface TacChatApi {
    suspend fun myIncidentMessages(incidentId: Long, limit: Int, beforeId: Long?): List<IncidentMessage>
    suspend fun sendIncidentMessage(incidentId: Long, message: String, clientMessageId: String): IncidentMessage
    suspend fun sendIncidentImageMessage(
        incidentId: Long,
        senderId: Long,
        message: String,
        clientMessageId: String,
        mimeType: String,
        imageBytes: ByteArray,
    ): IncidentMessage
    suspend fun downloadIncidentImage(path: String): ByteArray
}

interface TacApi {
    @POST("login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("logout")
    suspend fun logout()

    /** Allows local-first sign-out to revoke the captured session after clearing local credentials. */
    suspend fun logout(token: String?) { logout() }

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

    /** Dispatcher only: latest reported locations for active en-route units. */
    @POST("me/active-responder-locations")
    suspend fun activeResponderLocations(): List<ResponderLocation>

    @POST("incidents")
    suspend fun createIncident(@Body body: CreateIncidentRequest): Incident

    /** Requester: cancel only while the incident is still pending dispatch assignment. */
    @POST("incidents/{id}/cancel")
    suspend fun cancelMyIncident(@Path("id") id: Long): Incident

    @POST("rest/v1/rpc/api_update_my_incident")
    suspend fun updateMyIncident(@Body body: UpdateMyIncidentRequest): Incident

    @PATCH("incidents/{id}/status")
    suspend fun updateIncidentStatus(
        @Path("id") id: Long,
        @Body body: UpdateStatusRequest,
    ): Incident

    /** Assigned responder/driver: advance only their own response-team status. */
    @POST("incidents/{id}/assignment-status")
    suspend fun updateMyAssignmentStatus(
        @Path("id") id: Long,
        @Body body: UpdateStatusRequest,
    ): Incident

    /** Dispatcher: assign an available responder or driver to an incident. */
    @POST("incidents/{id}/assign")
    suspend fun assignIncident(
        @Path("id") id: Long,
        @Body body: AssignIncidentRequest,
    ): Incident

    /** Dispatcher: atomically assign a response team (1–5 available units). */
    @POST("incidents/{id}/assign-team")
    suspend fun assignIncidentTeam(@Path("id") id: Long, @Body body: AssignIncidentTeamRequest): Incident

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
    @SerializedName("location_source") val locationSource: String = "gps",
    @SerializedName("p_client_request_id") val clientRequestId: String,
)

data class UpdateStatusRequest(val status: IncidentStatus)

data class UpdateMyIncidentRequest(
    @SerializedName("p_id") val id: Long,
    @SerializedName("p_type") val type: String,
    @SerializedName("p_description") val description: String?,
    @SerializedName("p_latitude") val latitude: Double?,
    @SerializedName("p_longitude") val longitude: Double?,
    @SerializedName("p_accuracy_m") val accuracyM: Float?,
    @SerializedName("p_location_notes") val locationNotes: String?,
    @SerializedName("p_location_recorded_at") val locationRecordedAt: String?,
    @SerializedName("p_location_source") val locationSource: String,
    @SerializedName("p_confirm_review") val confirmReview: Boolean,
)

data class AssignIncidentRequest(@SerializedName("user_id") val userId: Long)

data class AssignIncidentTeamRequest(@SerializedName("user_ids") val userIds: List<Long>)

data class ResponderLocationRequest(
    @SerializedName("incident_id") val incidentId: Long,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("accuracy_m") val accuracyM: Float?,
    @SerializedName("recorded_at") val recordedAt: String,
)

/** Supabase-only privileged capability. Never route these operations through the Laravel bridge. */
interface TacAdminApi {
    suspend fun adminUsersPage(limit: Int, offset: Int, query: String, role: String): AdminUsersPage
    suspend fun adminAuditLogs(limit: Int): List<AdminAuditEvent>
    suspend fun setAdminUserRole(userId: Long, role: String, reason: String): AdminRoleChangeResult
}

data class AdminUser(
    val id: Long,
    val name: String,
    val email: String,
    val phone: String?,
    val role: String,
    @SerializedName("duty_status") val dutyStatus: String?,
    @SerializedName("created_at") val createdAt: String?,
)

data class AdminUsersPage(
    val users: List<AdminUser>,
    @SerializedName("total_count") val totalCount: Long,
)

data class AdminAuditEvent(
    val id: Long,
    @SerializedName("actor_user_id") val actorUserId: Long?,
    @SerializedName("actor_identity") val actorIdentity: String,
    @SerializedName("target_user_id") val targetUserId: Long,
    val action: String,
    @SerializedName("old_role") val oldRole: String,
    @SerializedName("new_role") val newRole: String,
    val reason: String,
    @SerializedName("created_at") val createdAt: String,
)

data class AdminRoleChangeResult(
    val id: Long,
    val name: String,
    val email: String,
    @SerializedName("old_role") val oldRole: String,
    val role: String,
    @SerializedName("duty_status") val dutyStatus: String?,
)
