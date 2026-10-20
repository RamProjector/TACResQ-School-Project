package com.tacresq.app.data.remote

import com.google.gson.annotations.SerializedName
import com.google.gson.GsonBuilder
import com.tacresq.app.AppConfig
import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.User
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Header
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Supabase Auth + PostgREST RPC adapter; only the publishable key is embedded in the app. */
class SupabaseTacApi private constructor(
    private val auth: SupabaseAuthWire,
    private val rpc: SupabaseRpcWire,
) : TacApi {
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
        val profile = rpc.me("Bearer $token")
        return AuthResponse(token = token, user = profile)
    }

    override suspend fun logout() { auth.logout() }
    override suspend fun me(): User = rpc.me()
    override suspend fun setDutyStatus(body: DutyStatusRequest): User =
        rpc.setDutyStatus(DutyStatusRpc(body.dutyStatus.wireValue))
    override suspend fun myIncidents(): List<Incident> = rpc.myIncidents(EmptyRpcBody())
    override suspend fun allIncidents(): List<Incident> = rpc.allIncidents(EmptyRpcBody())
    override suspend fun units(): List<User> = rpc.units(EmptyRpcBody())
    override suspend fun createIncident(body: CreateIncidentRequest): Incident = rpc.createIncidentV2(
        CreateIncidentV2Rpc(
            body.type.wireValue,
            body.description,
            body.latitude,
            body.longitude,
            body.accuracyM,
            body.locationNotes,
            body.locationRecordedAt,
        )
    )
    override suspend fun updateIncidentStatus(id: Long, body: UpdateStatusRequest): Incident =
        rpc.updateIncidentStatus(UpdateIncidentRpc(id, body.status.wireValue))
    override suspend fun assignIncident(id: Long, body: AssignIncidentRequest): Incident =
        rpc.assignIncident(AssignIncidentRpc(id, body.userId))
    override suspend fun recordLocation(body: ResponderLocationRequest): Long =
        rpc.recordLocation(RecordLocationRpc(body.incidentId, body.latitude, body.longitude, body.accuracyM, body.recordedAt))

    companion object {
        fun create(tokenProvider: () -> String?): TacApi {
            val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            val common = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("apikey", AppConfig.SUPABASE_PUBLISHABLE_KEY)
                        .header("Accept", "application/json")
                    tokenProvider()?.let { request.header("Authorization", "Bearer $it") }
                    chain.proceed(request.build())
                }
                .addInterceptor(logging)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(AppConfig.SUPABASE_URL.trimEnd('/') + "/")
                .client(common)
                .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
                .build()
            return SupabaseTacApi(retrofit.create(SupabaseAuthWire::class.java), retrofit.create(SupabaseRpcWire::class.java))
        }
    }
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

    @POST("rest/v1/rpc/api_create_incident_v2")
    suspend fun createIncidentV2(@Body body: CreateIncidentV2Rpc): Incident

    @POST("rest/v1/rpc/api_update_incident_status")
    suspend fun updateIncidentStatus(@Body body: UpdateIncidentRpc): Incident

    @POST("rest/v1/rpc/api_assign_incident")
    suspend fun assignIncident(@Body body: AssignIncidentRpc): Incident

    @POST("rest/v1/rpc/api_record_location_v2")
    suspend fun recordLocation(@Body body: RecordLocationRpc): Long
}

private data class SupabaseSession(@SerializedName("access_token") val accessToken: String?)
private data class SignupBody(val email: String, val password: String, val data: Map<String, String>)
private class EmptyRpcBody
private data class DutyStatusRpc(@SerializedName("p_duty_status") val status: String)
private data class CreateIncidentV2Rpc(
    @SerializedName("p_type") val type: String,
    @SerializedName("p_description") val description: String?,
    @SerializedName("p_latitude") val latitude: Double?,
    @SerializedName("p_longitude") val longitude: Double?,
    @SerializedName("p_accuracy_m") val accuracyM: Float?,
    @SerializedName("p_location_notes") val locationNotes: String?,
    @SerializedName("p_location_recorded_at") val locationRecordedAt: String?,
)
private data class UpdateIncidentRpc(@SerializedName("p_id") val id: Long, @SerializedName("p_status") val status: String)
private data class AssignIncidentRpc(@SerializedName("p_id") val id: Long, @SerializedName("p_user_id") val userId: Long)
private data class RecordLocationRpc(
    @SerializedName("p_incident_id") val incidentId: Long,
    @SerializedName("p_latitude") val latitude: Double,
    @SerializedName("p_longitude") val longitude: Double,
    @SerializedName("p_accuracy_m") val accuracyM: Float?,
    @SerializedName("p_recorded_at") val recordedAt: String,
)

private val DutyStatus.wireValue: String get() = when (this) {
    DutyStatus.OFF_DUTY -> "off_duty"
    DutyStatus.AVAILABLE -> "available"
    DutyStatus.EN_ROUTE -> "en_route"
    DutyStatus.ON_SCENE -> "on_scene"
    DutyStatus.TRANSPORTING -> "transporting"
}
private val com.tacresq.app.data.model.IncidentType.wireValue: String get() = name.lowercase()
private val com.tacresq.app.data.model.IncidentStatus.wireValue: String get() = name.lowercase()
