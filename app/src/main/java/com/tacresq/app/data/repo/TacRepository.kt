package com.tacresq.app.data.repo

import com.google.gson.Gson
import com.tacresq.app.data.local.SessionStore
import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.GeoFix
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.IncidentType
import com.tacresq.app.data.model.Session
import com.tacresq.app.data.model.SessionState
import com.tacresq.app.data.model.User
import com.tacresq.app.data.remote.AuthResponse
import com.tacresq.app.data.remote.AssignIncidentRequest
import com.tacresq.app.data.remote.CreateIncidentRequest
import com.tacresq.app.data.remote.DutyStatusRequest
import com.tacresq.app.data.remote.LoginRequest
import com.tacresq.app.data.remote.RegisterRequest
import com.tacresq.app.data.remote.TacApi
import com.tacresq.app.data.remote.UpdateStatusRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant

/** Holds the current bearer token so the HTTP layer can read it without depending on the repository. */
class TokenHolder {
    @Volatile
    var value: String? = null
}

class ApiException(message: String, val httpCode: Int? = null) : Exception(message)

class TacRepository(
    private val api: TacApi,
    private val store: SessionStore,
    private val tokenHolder: TokenHolder,
    appScope: CoroutineScope,
) {
    /** Loading until the saved session is read, then LoggedOut or LoggedIn. */
    val sessionState: StateFlow<SessionState> = store.session
        .onEach { session -> tokenHolder.value = session?.token }
        .map<Session?, SessionState> { session ->
            if (session == null) SessionState.LoggedOut else SessionState.LoggedIn(session)
        }
        .stateIn(appScope, SharingStarted.Eagerly, SessionState.Loading)

    suspend fun login(email: String, password: String): Result<Unit> = call {
        startSession(api.login(LoginRequest(email.trim(), password)))
    }

    suspend fun register(name: String, email: String, phone: String, password: String): Result<Unit> = call {
        startSession(api.register(RegisterRequest(name.trim(), email.trim(), phone.trim(), password)))
    }

    suspend fun logout() {
        runCatching { withContext(Dispatchers.IO) { api.logout() } }
        endSession()
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

    suspend fun createIncident(
        type: IncidentType,
        description: String?,
        fix: GeoFix?,
        locationNotes: String?,
    ): Result<Incident> = call {
        api.createIncident(
            CreateIncidentRequest(
                type = type,
                description = description,
                latitude = fix?.latitude,
                longitude = fix?.longitude,
                accuracyM = fix?.accuracyM,
                locationNotes = locationNotes?.trim()?.ifBlank { null },
                locationRecordedAt = fix?.let { Instant.ofEpochMilli(it.capturedAtEpochMillis).toString() },
            )
        )
    }

    suspend fun updateIncidentStatus(id: Long, status: IncidentStatus): Result<Incident> = call {
        api.updateIncidentStatus(id, UpdateStatusRequest(status))
    }

    suspend fun assignIncident(incidentId: Long, userId: Long): Result<Incident> = call {
        api.assignIncident(incidentId, AssignIncidentRequest(userId))
    }

    // ---- internals ----

    private suspend fun startSession(response: AuthResponse) {
        tokenHolder.value = response.token
        store.save(Session(response.token, response.user))
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
        } catch (e: Exception) {
            Result.failure(ApiException(e.message ?: "Something went wrong."))
        }
    }

    private data class ErrorBody(val message: String?, val errors: Map<String, List<String>>?)

    /** Reads Laravel's error JSON: first validation error, else "message". */
    private fun errorMessage(e: HttpException): String {
        val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val body = runCatching { raw?.let { Gson().fromJson(it, ErrorBody::class.java) } }.getOrNull()
        return body?.errors?.values?.firstOrNull()?.firstOrNull()
            ?: body?.message
            ?: "Request failed (HTTP ${e.code()})"
    }
}
