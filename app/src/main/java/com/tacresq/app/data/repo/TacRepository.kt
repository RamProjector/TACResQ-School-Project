package com.tacresq.app.data.repo

import com.google.gson.Gson
import com.tacresq.app.data.local.SessionStore
import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.GeoFix
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.IncidentType
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.Session
import com.tacresq.app.data.model.SessionState
import com.tacresq.app.data.model.User
import com.tacresq.app.data.model.UserNotification
import com.tacresq.app.data.model.IncidentMessage
import com.tacresq.app.data.remote.AuthResponse
import com.tacresq.app.data.remote.AssignIncidentTeamRequest
import com.tacresq.app.data.remote.CreateIncidentRequest
import com.tacresq.app.data.remote.DutyStatusRequest
import com.tacresq.app.data.remote.LoginRequest
import com.tacresq.app.data.remote.RegisterRequest
import com.tacresq.app.data.remote.ResponderLocationRequest
import com.tacresq.app.data.remote.TacApi
import com.tacresq.app.data.remote.TacNotificationApi
import com.tacresq.app.data.remote.TacChatApi
import com.tacresq.app.data.remote.UpdateStatusRequest
import com.tacresq.app.data.remote.UpdateMyIncidentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant

/** Holds the current bearer token so the HTTP layer can read it without depending on the repository. */
class TokenHolder {
    @Volatile
    var value: String? = null
}

class ApiException(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Only discard a saved SOS after a response that definitively rejects the operation.
 * Authentication, throttling, timeout, and conflict responses must retain the exact
 * request envelope so the user can recover without creating a second emergency request.
 */
internal fun isDefinitiveIncidentRejection(httpCode: Int): Boolean =
    httpCode in 400..499 && httpCode !in setOf(400, 401, 403, 408, 409, 425, 429)

/** User-safe sign-in messages that distinguish credentials, throttling, and service outages. */
internal fun signInHttpErrorMessage(httpCode: Int): String = when {
    httpCode == 429 -> "Too many sign-in attempts. Wait a moment and try again."
    httpCode >= 500 -> "The sign-in service is temporarily unavailable. Please try again."
    httpCode in 400..401 -> "Email or password is incorrect."
    else -> "Sign-in couldn't be completed. Check your email confirmation or try again."
}

class TacRepository(
    private val api: TacApi,
    private val store: SessionStore,
    private val tokenHolder: TokenHolder,
    private val appScope: CoroutineScope,
) : NotificationRepository {
    /** Loading until the saved session is read, with a recoverable state on persistent I/O failure. */
    val sessionState: StateFlow<SessionState> = combine(
        store.session,
        store.sessionReadError,
        store.sessionReadLoading,
    ) { session, readError, readLoading ->
        when {
            readLoading -> SessionState.Loading
            readError -> SessionState.RestoreFailed
            session == null -> SessionState.LoggedOut
            else -> SessionState.LoggedIn(session)
        }
    }
        .onEach { state ->
            tokenHolder.value = (state as? SessionState.LoggedIn)?.session?.token
        }
        .stateIn(appScope, SharingStarted.Eagerly, SessionState.Loading)

    /** Re-run the bounded DataStore restore attempt after a visible storage error. */
    fun retrySessionRestore() = store.retrySessionRead()

    suspend fun login(email: String, password: String): Result<Unit> = call {
        startSession(api.login(LoginRequest(email.trim(), password)))
    }

    suspend fun register(name: String, email: String, phone: String, password: String): Result<Unit> = call {
        startSession(api.register(RegisterRequest(name.trim(), email.trim(), phone.trim(), password)))
    }

    suspend fun logout() {
        // Clear local access immediately; remote revocation must never block sign-out UI.
        val capturedToken = tokenHolder.value
        endSession()
        if (capturedToken.isNullOrBlank()) return
        // The injected application scope outlives this screen, so network revocation
        // cannot delay navigation away from the signed-in UI or be cancelled with it.
        appScope.launch(Dispatchers.IO) {
            try {
                api.logout(capturedToken)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Local credentials are already cleared. Server-side token expiry/revocation
                // remains authoritative even if this best-effort network call cannot complete.
            }
        }
    }

    suspend fun me(): Result<User> = call { api.me() }

    suspend fun setDutyStatus(status: DutyStatus): Result<User> = call {
        val user = api.setDutyStatus(DutyStatusRequest(status))
        tokenHolder.value?.let { token -> store.save(Session(token, user)) }
        user
    }

    suspend fun myIncidents(): Result<List<Incident>> = call { api.myIncidents() }

    suspend fun allIncidents(): Result<List<Incident>> = call { api.allIncidents() }

    suspend fun units(): Result<List<User>> = call { api.units() }

    suspend fun activeResponderLocations(): Result<List<ResponderLocation>> = call { api.activeResponderLocations() }

    override suspend fun myNotifications(limit: Int): Result<List<UserNotification>> = call {
        val notificationApi = api as? TacNotificationApi
            ?: throw ApiException("Notifications are not available with the configured backend.")
        require(limit in 1..100) { "Notification page size must be between 1 and 100." }
        notificationApi.myNotifications(limit)
    }

    override suspend fun markNotificationRead(notificationId: Long): Result<Boolean> = call {
        val notificationApi = api as? TacNotificationApi
            ?: throw ApiException("Notifications are not available with the configured backend.")
        require(notificationId > 0) { "Notification ID must be positive." }
        notificationApi.markNotificationRead(notificationId)
    }

    suspend fun myIncidentMessages(
        incidentId: Long,
        limit: Int = 50,
        beforeId: Long? = null,
    ): Result<List<IncidentMessage>> = call {
        val chatApi = api as? TacChatApi
            ?: throw ApiException("Incident chat is not available with the configured backend.")
        require(incidentId > 0) { "Incident ID must be positive." }
        require(limit in 1..100) { "Message page size must be between 1 and 100." }
        require(beforeId == null || beforeId > 0) { "Message cursor must be positive." }
        chatApi.myIncidentMessages(incidentId, limit, beforeId)
    }

    suspend fun sendIncidentMessage(
        incidentId: Long,
        message: String,
        clientMessageId: String,
    ): Result<IncidentMessage> = call {
        val chatApi = api as? TacChatApi
            ?: throw ApiException("Incident chat is not available with the configured backend.")
        val normalized = message.trim()
        require(incidentId > 0) { "Incident ID must be positive." }
        require(normalized.length in 1..2000) { "Message must contain 1 to 2000 characters." }
        chatApi.sendIncidentMessage(incidentId, normalized, clientMessageId)
    }

    suspend fun createIncident(
        type: IncidentType,
        description: String?,
        fix: GeoFix?,
        locationNotes: String?,
    ): Result<Incident> {
        val normalizedDescription = description?.trim()?.ifBlank { null }
        val normalizedNotes = locationNotes?.trim()?.take(500)?.ifBlank { null }
        val capturedAt = fix?.let { Instant.ofEpochMilli(it.capturedAtEpochMillis).toString() }
        val source = when { fix == null -> "none"; fix.manuallySelected -> "manual"; else -> "gps" }
        val candidate = CreateIncidentRequest(
            type = type,
            description = normalizedDescription,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            accuracyM = fix?.accuracyM,
            locationNotes = normalizedNotes,
            locationRecordedAt = capturedAt,
            locationSource = source,
            clientRequestId = "",
        )
        val payloadJson = Gson().toJson(candidate)
        val fingerprint = payloadJson
        val ownerUserId = (sessionState.value as? SessionState.LoggedIn)?.session?.user?.id
            ?: return Result.failure(ApiException("Sign in as a requester before submitting an emergency request."))
        val pending = try {
            store.getOrCreatePendingIncident(fingerprint, payloadJson, ownerUserId)
        } catch (e: SessionStore.PendingIncidentConflictException) {
            return Result.failure(ApiException(e.message ?: "An earlier SOS submission is unresolved."))
        }
        val request = runCatching { Gson().fromJson(pending.payloadJson, CreateIncidentRequest::class.java).copy(clientRequestId = pending.requestId) }
            .getOrElse { return Result.failure(ApiException("Could not restore the pending SOS submission. Please contact support before creating another request.")) }
        val result = call { api.createIncident(request) }
        if (result.isSuccess) {
            // Only a confirmed server success clears the durable envelope. Rejections and
            // ambiguous failures remain available for explicit, owner-checked resolution.
            store.clearPendingIncidentRequestId(pending.requestId, ownerUserId)
        }
        return result
    }

    /** Retries the persisted SOS verbatim, including its original location and idempotency key. */
    suspend fun retryPendingIncident(): Result<Incident> {
        val ownerUserId = (sessionState.value as? SessionState.LoggedIn)?.session?.user?.id
            ?: return Result.failure(ApiException("Sign in with the original requester account to retry this saved SOS."))
        val pending = try {
            store.getPendingIncidentSubmission(ownerUserId)
        } catch (e: SessionStore.PendingIncidentConflictException) {
            return Result.failure(ApiException(e.message ?: "The saved SOS could not be restored safely."))
        } catch (e: Exception) {
            return Result.failure(ApiException("The saved SOS could not be restored safely. Please contact dispatch before creating another request."))
        } ?: return Result.failure(ApiException("There is no saved SOS to retry."))
        val request = runCatching {
            Gson().fromJson(pending.payloadJson, CreateIncidentRequest::class.java)
                .copy(clientRequestId = pending.requestId)
        }.getOrElse {
            return Result.failure(ApiException("The saved SOS payload is invalid. Do not create a replacement request; contact dispatch."))
        }
        val result = call { api.createIncident(request) }
        if (result.isSuccess) {
            store.clearPendingIncidentRequestId(pending.requestId, ownerUserId)
        }
        return result
    }

    suspend fun hasPendingIncident(): Boolean {
        val ownerUserId = (sessionState.value as? SessionState.LoggedIn)?.session?.user?.id
            ?: throw ApiException("Sign in with the original requester account to check saved SOS status.")
        return try {
            store.getPendingIncidentSubmission(ownerUserId) != null
        } catch (e: SessionStore.PendingIncidentConflictException) {
            throw ApiException(e.message ?: "A saved SOS cannot be used by this requester account.")
        }
    }

    suspend fun discardPendingIncident(): Result<Unit> = call {
        val ownerUserId = (sessionState.value as? SessionState.LoggedIn)?.session?.user?.id
            ?: throw ApiException("Sign in with the original requester account to discard its saved SOS.")
        val pending = store.getPendingIncidentSubmission(ownerUserId)
            ?: return@call Unit
        store.clearPendingIncidentRequestId(pending.requestId, ownerUserId)
    }

    suspend fun updateIncidentStatus(id: Long, status: IncidentStatus): Result<Incident> = call {
        api.updateMyAssignmentStatus(id, UpdateStatusRequest(status))
    }

    suspend fun cancelMyIncident(id: Long): Result<Incident> = call {
        api.cancelMyIncident(id)
    }

    suspend fun updateMyIncident(
        incident: Incident,
        type: IncidentType,
        description: String?,
        locationNotes: String?,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Float?,
        locationRecordedAt: String?,
        locationSource: String,
        confirmReview: Boolean,
    ): Result<Incident> = call {
        api.updateMyIncident(
            UpdateMyIncidentRequest(
                id = incident.id,
                type = type.name.lowercase(),
                description = description?.trim()?.take(2000)?.ifBlank { null },
                latitude = latitude,
                longitude = longitude,
                accuracyM = accuracyM,
                locationNotes = locationNotes?.trim()?.take(500)?.ifBlank { null },
                locationRecordedAt = locationRecordedAt,
                locationSource = locationSource,
                confirmReview = confirmReview,
            )
        )
    }

    suspend fun assignIncident(incidentId: Long, userIds: List<Long>): Result<Incident> = call {
        require(userIds.isNotEmpty() && userIds.size <= 5 && userIds.distinct().size == userIds.size) {
            "Select between 1 and 5 different field units."
        }
        api.assignIncidentTeam(incidentId, AssignIncidentTeamRequest(userIds))
    }

    /** Reserved for the explicit foreground tracking lifecycle; callers must gate this on en_route. */
    suspend fun recordResponderLocation(incidentId: Long, fix: GeoFix): Result<Long> = call {
        api.recordLocation(
            ResponderLocationRequest(
                incidentId = incidentId,
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracyM = fix.accuracyM,
                recordedAt = Instant.ofEpochMilli(fix.capturedAtEpochMillis).toString(),
            )
        )
    }


    suspend fun adminUsersPage(limit: Int = 25, offset: Int = 0, query: String = "", role: String = "all") =
        call {
            val adminApi = api as? com.tacresq.app.data.remote.TacAdminApi
                ?: throw ApiException("Administrator tools require the authenticated Supabase backend.")
            adminApi.adminUsersPage(limit, offset, query.trim(), role)
        }

    suspend fun adminAuditLogs(limit: Int = 100) = call {
        val adminApi = api as? com.tacresq.app.data.remote.TacAdminApi
            ?: throw ApiException("Administrator tools require the authenticated Supabase backend.")
        adminApi.adminAuditLogs(limit)
    }

    suspend fun setAdminUserRole(userId: Long, role: String, reason: String) = call {
        val adminApi = api as? com.tacresq.app.data.remote.TacAdminApi
            ?: throw ApiException("Administrator tools require the authenticated Supabase backend.")
        adminApi.setAdminUserRole(userId, role, reason)
    }

    // ---- internals ----

    private suspend fun startSession(response: AuthResponse) {
        // Persist first: if Keystore encryption/storage fails, don't leave a valid bearer
        // token in memory while the UI correctly treats the user as signed out.
        store.save(Session(response.token, response.user))
        tokenHolder.value = response.token
    }

    private suspend fun endSession() {
        tokenHolder.value = null
        store.clear()
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> {
        return try {
            Result.success(withContext(Dispatchers.IO) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            // An expired or revoked token sends the user back to the login screen.
            if (e.code() == 401 && tokenHolder.value != null) endSession()
            Result.failure(ApiException(errorMessage(e), e.code()))
        } catch (e: IOException) {
            Result.failure(ApiException("No connection. Check your network and try again."))
        } catch (e: ApiException) {
            // Preserve deliberate, user-safe validation and feature-availability messages.
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            // Do not surface arbitrary parser/library exception text to the user.
            Result.failure(ApiException("Please check the information you entered and try again."))
        } catch (e: Exception) {
            // Unexpected exceptions may contain SQL, provider, or implementation details.
            Result.failure(ApiException("Something went wrong. Please try again."))
        }
    }

    private data class ErrorBody(val message: String?, val errors: Map<String, List<String>>?)

    /** Reads API validation messages without leaking raw PostgreSQL/PostgREST internals to the UI. */
    private fun errorMessage(e: HttpException): String {
        // Auth endpoints must not reveal whether an email address is registered.
        // Keep the response generic even when Supabase returns a more specific
        // provider message such as "User already registered".
        val authPath = e.response()?.raw()?.request?.url?.encodedPath.orEmpty()
        if (authPath.endsWith("/auth/v1/token")) {
            return signInHttpErrorMessage(e.code())
        }
        if (authPath.endsWith("/auth/v1/signup")) {
            return if (e.code() == 429) {
                "Too many sign-up attempts. Wait a moment and try again."
            } else {
                "We couldn't complete sign-up. Check your details or try again later."
            }
        }

        val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val body = runCatching { raw?.let { Gson().fromJson(it, ErrorBody::class.java) } }.getOrNull()
        val message = body?.errors?.values?.firstOrNull()?.firstOrNull()
            ?: body?.message
            ?: "Request failed (HTTP ${e.code()})"
        val normalized = message.lowercase()
        val internalDatabaseError = listOf(
            "violates foreign key constraint",
            "violates check constraint",
            "violates unique constraint",
            "insert or update on table",
            "sql statement",
            "postgres",
            "postgrest",
            "pgrst",
            "permission denied for table",
            "relation public.",
            "function public.",
        ).any(normalized::contains)

        return if (internalDatabaseError) {
            "The server couldn't complete this request. Please try again. If this is an emergency, contact local emergency services directly."
        } else {
            message
        }
    }
}
