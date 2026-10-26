package com.tacresq.app.data.remote

import com.google.gson.annotations.SerializedName
import com.google.gson.GsonBuilder
import com.tacresq.app.AppConfig
import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.User
import com.tacresq.app.data.model.UserNotification
import com.tacresq.app.data.model.IncidentMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Header
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Supabase Auth + optional authenticated Render RPC transport; only the publishable key is embedded in the app. */
class SupabaseTacApi private constructor(
    private val auth: SupabaseAuthWire,
    private val rpc: SupabaseRpcWire,
    private val bridgeRpc: SupabaseBridgeRpcWire?,
) : TacApi, TacNotificationApi, TacChatApi {
    override suspend fun login(body: LoginRequest): AuthResponse = authenticate {
        auth.login(body)
    }

    override suspend fun register(body: RegisterRequest): AuthResponse = authenticate {
        auth.signup(SignupBody(data = mapOf("name" to body.name, "phone" to body.phone, "role" to "requester"), email = body.email, password = body.password))
    }

    private suspend fun authenticate(request: suspend () -> SupabaseSession): AuthResponse {
        val result = request()
        val token = result.accessToken ?: throw IllegalStateException(
            "Account created. Check your email and tap the confirmation link, then return to TACResQ and sign in."
        )
        val profile = if (bridgeRpc != null) {
            bridgeRpc.me(EmptyRpcBody(), "Bearer $token")
        } else {
            rpc.me("Bearer $token")
        }
        return AuthResponse(token = token, user = profile)
    }

    override suspend fun logout() { auth.logout() }
    override suspend fun me(): User =
        if (bridgeRpc != null) bridgeRpc.me(EmptyRpcBody()) else rpc.me()

    override suspend fun setDutyStatus(body: DutyStatusRequest): User {
        val request = DutyStatusRpc(body.dutyStatus.wireValue)
        return if (bridgeRpc != null) bridgeRpc.setDutyStatus(request) else rpc.setDutyStatus(request)
    }

    override suspend fun myIncidents(): List<Incident> =
        if (bridgeRpc != null) bridgeRpc.myIncidents(EmptyRpcBody()) else rpc.myIncidents(EmptyRpcBody())

    override suspend fun allIncidents(): List<Incident> =
        if (bridgeRpc != null) bridgeRpc.allIncidents(EmptyRpcBody()) else rpc.allIncidents(EmptyRpcBody())

    override suspend fun units(): List<User> =
        if (bridgeRpc != null) bridgeRpc.units(EmptyRpcBody()) else rpc.units(EmptyRpcBody())

    override suspend fun activeResponderLocations(): List<ResponderLocation> =
        if (bridgeRpc != null) bridgeRpc.activeResponderLocations(EmptyRpcBody()) else rpc.activeResponderLocations(EmptyRpcBody())
    override suspend fun createIncident(body: CreateIncidentRequest): Incident {
        val request = CreateIncidentV4Rpc(
            body.type.wireValue,
            body.description,
            body.latitude,
            body.longitude,
            body.accuracyM,
            body.locationNotes,
            body.locationRecordedAt,
            body.locationSource,
            body.clientRequestId,
        )
        return if (bridgeRpc != null) bridgeRpc.createIncidentV4(request) else rpc.createIncidentV4(request)
    }
    override suspend fun cancelMyIncident(id: Long): Incident {
        val request = CancelIncidentRpc(id)
        return if (bridgeRpc != null) bridgeRpc.cancelMyIncident(request) else rpc.cancelMyIncident(request)
    }
    override suspend fun updateMyIncident(body: UpdateMyIncidentRequest): Incident =
        if (bridgeRpc != null) bridgeRpc.updateMyIncident(body) else rpc.updateMyIncident(body)
    override suspend fun updateIncidentStatus(id: Long, body: UpdateStatusRequest): Incident {
        val request = UpdateIncidentRpc(id, body.status.wireValue)
        return if (bridgeRpc != null) bridgeRpc.updateIncidentStatus(request) else rpc.updateIncidentStatus(request)
    }

    override suspend fun updateMyAssignmentStatus(id: Long, body: UpdateStatusRequest): Incident {
        val request = UpdateMyAssignmentStatusRpc(id, body.status.wireValue)
        return if (bridgeRpc != null) bridgeRpc.updateMyAssignmentStatus(request) else rpc.updateMyAssignmentStatus(request)
    }

    override suspend fun assignIncident(id: Long, body: AssignIncidentRequest): Incident {
        val request = AssignIncidentRpc(id, body.userId)
        return if (bridgeRpc != null) bridgeRpc.assignIncident(request) else rpc.assignIncident(request)
    }

    override suspend fun assignIncidentTeam(id: Long, body: AssignIncidentTeamRequest): Incident {
        val request = AssignIncidentTeamRpc(id, body.userIds)
        return if (bridgeRpc != null) bridgeRpc.assignIncidentTeam(request) else rpc.assignIncidentTeam(request)
    }

    override suspend fun recordLocation(body: ResponderLocationRequest): Long {
        val request = RecordLocationRpc(body.incidentId, body.latitude, body.longitude, body.accuracyM, body.recordedAt)
        return if (bridgeRpc != null) bridgeRpc.recordLocation(request) else rpc.recordLocation(request)
    }

    override suspend fun myNotifications(limit: Int): List<UserNotification> {
        require(limit in 1..100) { "Notification page size must be between 1 and 100." }
        // The current Render bridge allowlist has no notification routes yet. Use the
        // authenticated Supabase RPC directly; the same stored session token is applied.
        return rpc.myNotifications(NotificationsLimitRpc(limit))
    }

    override suspend fun markNotificationRead(notificationId: Long): Boolean {
        require(notificationId > 0) { "Notification ID must be positive." }
        return rpc.markNotificationRead(MarkNotificationReadRpc(notificationId))
    }

    override suspend fun myIncidentMessages(incidentId: Long, limit: Int, beforeId: Long?): List<IncidentMessage> {
        require(incidentId > 0) { "Incident ID must be positive." }
        require(limit in 1..100) { "Message page size must be between 1 and 100." }
        require(beforeId == null || beforeId > 0) { "Message cursor must be positive." }
        return rpc.myIncidentMessages(IncidentMessagesRpc(incidentId, limit, beforeId))
    }

    override suspend fun sendIncidentMessage(incidentId: Long, message: String, clientMessageId: String): IncidentMessage {
        require(incidentId > 0) { "Incident ID must be positive." }
        val normalized = message.trim()
        require(normalized.length in 1..2000) { "Message must contain 1 to 2000 characters." }
        java.util.UUID.fromString(clientMessageId)
        return rpc.sendIncidentMessage(SendIncidentMessageRpc(incidentId, normalized, clientMessageId))
    }

    companion object {
        fun create(tokenProvider: () -> String?, useRenderBridge: Boolean = false): TacApi {
            val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            val common = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("apikey", AppConfig.SUPABASE_PUBLISHABLE_KEY)
                        .header("Accept", "application/json")
                    chain.proceed(withStoredSessionAuthorization(request.build(), tokenProvider))
                }
                .addInterceptor(logging)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val gson = GsonBuilder().serializeNulls().create()
            val retrofit = Retrofit.Builder()
                .baseUrl(AppConfig.SUPABASE_URL.trimEnd('/') + "/")
                .client(common)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
            val bridgeRpc = if (useRenderBridge) {
                val bridgeClient = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Accept", "application/json")
                        tokenProvider()?.let { request.header("Authorization", "Bearer $it") }
                        chain.proceed(request.build())
                    }
                    .addInterceptor(logging)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                Retrofit.Builder()
                    .baseUrl(AppConfig.BASE_URL)
                    .client(bridgeClient)
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .build()
                    .create(SupabaseBridgeRpcWire::class.java)
            } else null
            return SupabaseTacApi(
                retrofit.create(SupabaseAuthWire::class.java),
                retrofit.create(SupabaseRpcWire::class.java),
                bridgeRpc,
            )
        }
    }
}

/**
 * Preserve an explicit token from a just-completed login/signup before consulting
 * the persisted session provider. This prevents an older cached token from
 * overriding the fresh token used to load the authenticated profile.
 */
internal fun withStoredSessionAuthorization(
    original: Request,
    tokenProvider: () -> String?,
): Request {
    if (!original.header("Authorization").isNullOrBlank()) return original
    val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return original
    return original.newBuilder().header("Authorization", "Bearer $token").build()
}

private interface SupabaseAuthWire {
    @POST("auth/v1/token")
    suspend fun login(@Body body: LoginRequest, @Query("grant_type") grantType: String = "password"): SupabaseSession

    @POST("auth/v1/signup")
    suspend fun signup(
        @Body body: SignupBody,
        @Query("redirect_to") redirectTo: String = AppConfig.AUTH_REDIRECT_URL,
    ): SupabaseSession

    @POST("auth/v1/logout")
    suspend fun logout()
}

/**
 * Render transport for the full current application data-RPC surface. It is only
 * enabled in opt-in Supabase debug builds; release remains on direct Supabase until
 * live authenticated QA and a separately approved cutover.
 */
private interface SupabaseBridgeRpcWire {
    @POST("bridge/rpc/api_me")
    suspend fun me(@Body body: EmptyRpcBody, @Header("Authorization") authorization: String? = null): User

    @POST("bridge/rpc/api_set_duty_status")
    suspend fun setDutyStatus(@Body body: DutyStatusRpc): User

    @POST("bridge/rpc/api_my_incidents")
    suspend fun myIncidents(@Body body: EmptyRpcBody): List<Incident>

    @POST("bridge/rpc/api_all_incidents")
    suspend fun allIncidents(@Body body: EmptyRpcBody): List<Incident>

    @POST("bridge/rpc/api_active_responder_locations")
    suspend fun activeResponderLocations(@Body body: EmptyRpcBody): List<ResponderLocation>

    @POST("bridge/rpc/api_update_incident_status")
    suspend fun updateIncidentStatus(@Body body: UpdateIncidentRpc): Incident

    @POST("bridge/rpc/api_update_my_assignment_status")
    suspend fun updateMyAssignmentStatus(@Body body: UpdateMyAssignmentStatusRpc): Incident

    @POST("bridge/rpc/api_assign_incident")
    suspend fun assignIncident(@Body body: AssignIncidentRpc): Incident

    @POST("bridge/rpc/api_assign_incident_team")
    suspend fun assignIncidentTeam(@Body body: AssignIncidentTeamRpc): Incident

    @POST("bridge/rpc/api_record_location_v2")
    suspend fun recordLocation(@Body body: RecordLocationRpc): Long

    @POST("bridge/rpc/api_units")
    suspend fun units(@Body body: EmptyRpcBody): List<User>

    @POST("bridge/rpc/api_create_incident_v4")
    suspend fun createIncidentV4(@Body body: CreateIncidentV4Rpc): Incident

    @POST("bridge/rpc/api_cancel_my_incident")
    suspend fun cancelMyIncident(@Body body: CancelIncidentRpc): Incident

    @POST("bridge/rpc/api_update_my_incident")
    suspend fun updateMyIncident(@Body body: UpdateMyIncidentRequest): Incident
}

private interface SupabaseRpcWire {
    @POST("rest/v1/rpc/api_me")
    suspend fun me(@Header("Authorization") authorization: String? = null, @Body body: EmptyRpcBody = EmptyRpcBody()): User

    @POST("rest/v1/rpc/api_set_duty_status")
    suspend fun setDutyStatus(@Body body: DutyStatusRpc): User

    @POST("rest/v1/rpc/api_my_incidents")
    suspend fun myIncidents(@Body body: EmptyRpcBody): List<Incident>

    @POST("rest/v1/rpc/api_all_incidents")
    suspend fun allIncidents(@Body body: EmptyRpcBody): List<Incident>

    @POST("rest/v1/rpc/api_units")
    suspend fun units(@Body body: EmptyRpcBody): List<User>

    @POST("rest/v1/rpc/api_active_responder_locations")
    suspend fun activeResponderLocations(@Body body: EmptyRpcBody): List<ResponderLocation>

    @POST("rest/v1/rpc/api_create_incident_v4")
    suspend fun createIncidentV4(@Body body: CreateIncidentV4Rpc): Incident

    @POST("rest/v1/rpc/api_cancel_my_incident")
    suspend fun cancelMyIncident(@Body body: CancelIncidentRpc): Incident

    @POST("rest/v1/rpc/api_update_my_incident")
    suspend fun updateMyIncident(@Body body: UpdateMyIncidentRequest): Incident

    @POST("rest/v1/rpc/api_update_incident_status")
    suspend fun updateIncidentStatus(@Body body: UpdateIncidentRpc): Incident

    @POST("rest/v1/rpc/api_update_my_assignment_status")
    suspend fun updateMyAssignmentStatus(@Body body: UpdateMyAssignmentStatusRpc): Incident

    @POST("rest/v1/rpc/api_assign_incident")
    suspend fun assignIncident(@Body body: AssignIncidentRpc): Incident

    @POST("rest/v1/rpc/api_assign_incident_team")
    suspend fun assignIncidentTeam(@Body body: AssignIncidentTeamRpc): Incident

    @POST("rest/v1/rpc/api_record_location_v2")
    suspend fun recordLocation(@Body body: RecordLocationRpc): Long

    @POST("rest/v1/rpc/api_my_notifications")
    suspend fun myNotifications(@Body body: NotificationsLimitRpc): List<UserNotification>

    @POST("rest/v1/rpc/api_mark_notification_read")
    suspend fun markNotificationRead(@Body body: MarkNotificationReadRpc): Boolean

    @POST("rest/v1/rpc/api_my_incident_messages")
    suspend fun myIncidentMessages(@Body body: IncidentMessagesRpc): List<IncidentMessage>

    @POST("rest/v1/rpc/api_send_incident_message")
    suspend fun sendIncidentMessage(@Body body: SendIncidentMessageRpc): IncidentMessage
}

private data class SupabaseSession(@SerializedName("access_token") val accessToken: String?)
private data class SignupBody(val email: String, val password: String, val data: Map<String, String>)
private class EmptyRpcBody
private data class DutyStatusRpc(@SerializedName("p_duty_status") val status: String)
private data class CreateIncidentV4Rpc(
    @SerializedName("p_type") val type: String,
    @SerializedName("p_description") val description: String?,
    @SerializedName("p_latitude") val latitude: Double?,
    @SerializedName("p_longitude") val longitude: Double?,
    @SerializedName("p_accuracy_m") val accuracyM: Float?,
    @SerializedName("p_location_notes") val locationNotes: String?,
    @SerializedName("p_location_recorded_at") val locationRecordedAt: String?,
    @SerializedName("p_location_source") val locationSource: String,
    @SerializedName("p_client_request_id") val clientRequestId: String,
)
private data class CancelIncidentRpc(@SerializedName("p_id") val id: Long)
private data class UpdateIncidentRpc(@SerializedName("p_id") val id: Long, @SerializedName("p_status") val status: String)
private data class UpdateMyAssignmentStatusRpc(
    @SerializedName("p_incident_id") val incidentId: Long,
    @SerializedName("p_status") val status: String,
)
private data class AssignIncidentRpc(@SerializedName("p_id") val id: Long, @SerializedName("p_user_id") val userId: Long)
private data class AssignIncidentTeamRpc(@SerializedName("p_id") val id: Long, @SerializedName("p_user_ids") val userIds: List<Long>)
private data class RecordLocationRpc(
    @SerializedName("p_incident_id") val incidentId: Long,
    @SerializedName("p_latitude") val latitude: Double,
    @SerializedName("p_longitude") val longitude: Double,
    @SerializedName("p_accuracy_m") val accuracyM: Float?,
    @SerializedName("p_recorded_at") val recordedAt: String,
)
private data class NotificationsLimitRpc(@SerializedName("p_limit") val limit: Int)
private data class MarkNotificationReadRpc(@SerializedName("p_notification_id") val notificationId: Long)
private data class IncidentMessagesRpc(
    @SerializedName("p_incident_id") val incidentId: Long,
    @SerializedName("p_limit") val limit: Int,
    @SerializedName("p_before_id") val beforeId: Long?,
)
private data class SendIncidentMessageRpc(
    @SerializedName("p_incident_id") val incidentId: Long,
    @SerializedName("p_message") val message: String,
    @SerializedName("p_client_message_id") val clientMessageId: String,
)

private val DutyStatus.wireValue: String get() = when (this) {
    DutyStatus.OFF_DUTY -> "off_duty"
    DutyStatus.AVAILABLE -> "available"
    DutyStatus.ASSIGNED -> "assigned"
    DutyStatus.EN_ROUTE -> "en_route"
    DutyStatus.ON_SCENE -> "on_scene"
    DutyStatus.TRANSPORTING -> "transporting"
    DutyStatus.RESOLVED -> "resolved"
}
private val com.tacresq.app.data.model.IncidentType.wireValue: String get() = name.lowercase()
private val com.tacresq.app.data.model.IncidentStatus.wireValue: String get() = name.lowercase()
