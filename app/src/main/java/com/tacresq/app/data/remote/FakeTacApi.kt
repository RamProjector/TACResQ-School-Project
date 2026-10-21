package com.tacresq.app.data.remote

import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.User
import com.tacresq.app.data.model.nextSteps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.time.Instant
import java.util.UUID

/**
 * Fake server used when AppConfig.USE_FAKE_API = true. It follows the same rules as docs/API.md,
 * so the UI can be built and demoed before the Laravel API exists.
 *
 * Default demo flow: requester sends SOS -> dispatcher manually assigns an AVAILABLE unit ->
 * that unit steps the incident through its statuses. Optional auto-dispatch is controlled by
 * AppConfig.FAKE_AUTO_DISPATCH and is off by default.
 * Demo accounts (password demo123): requester@, responder@, driver@, dispatcher@demo.com
 */
class FakeTacApi(
    private val tokenProvider: () -> String?,
    private val autoDispatchEnabled: Boolean = false,
) : TacApi {

    private data class Account(val user: User, val password: String)

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val accounts = mutableListOf(
        Account(User(1, "Rica Requester", "requester@demo.com", "0917 123 4567", Role.REQUESTER), "demo123"),
        Account(User(2, "Rey Responder", "responder@demo.com", "0918 123 4567", Role.RESPONDER, DutyStatus.OFF_DUTY), "demo123"),
        Account(User(3, "Dan Driver", "driver@demo.com", "0919 123 4567", Role.DRIVER, DutyStatus.OFF_DUTY), "demo123"),
        Account(User(4, "Dina Dispatcher", "dispatcher@demo.com", "0920 123 4567", Role.DISPATCHER), "demo123"),
    )
    private val tokens = mutableMapOf<String, Long>()
    private val incidents = mutableListOf<Incident>()
    private val requesterOf = mutableMapOf<Long, Long>()
    private var nextUserId = 100L
    private var nextIncidentId = 1L

    override suspend fun login(body: LoginRequest): AuthResponse {
        delay(400)
        return synchronized(lock) {
            val account = accounts.firstOrNull {
                it.user.email.equals(body.email, ignoreCase = true) && it.password == body.password
            } ?: throw httpError(422, "These credentials do not match our records.")
            AuthResponse(issueToken(account.user.id), account.user)
        }
    }

    override suspend fun register(body: RegisterRequest): AuthResponse {
        delay(500)
        return synchronized(lock) {
            if (accounts.any { it.user.email.equals(body.email, ignoreCase = true) }) {
                throw httpError(422, "The email has already been taken.")
            }
            if (body.password.length < 8) {
                throw httpError(422, "The password must be at least 8 characters.")
            }
            val user = User(nextUserId++, body.name, body.email, body.phone, Role.REQUESTER)
            accounts += Account(user, body.password)
            AuthResponse(issueToken(user.id), user)
        }
    }

    override suspend fun logout() {
        delay(100)
        synchronized(lock) {
            tokenProvider()?.let { tokens.remove(it) }
        }
    }

    override suspend fun me(): User {
        delay(200)
        return synchronized(lock) { current() }
    }

    override suspend fun setDutyStatus(body: DutyStatusRequest): User {
        delay(300)
        val updated = synchronized(lock) {
            val me = current()
            if (me.role != Role.RESPONDER && me.role != Role.DRIVER) {
                throw httpError(403, "Only responders and drivers have a duty status.")
            }
            if (hasActiveIncident(me.id)) {
                throw httpError(422, "Finish your current incident first.")
            }
            if (body.dutyStatus != DutyStatus.AVAILABLE && body.dutyStatus != DutyStatus.OFF_DUTY) {
                throw httpError(422, "Duty status changes automatically while you are on an incident.")
            }
            setDuty(me.id, body.dutyStatus)
            current()
        }
        if (updated.dutyStatus == DutyStatus.AVAILABLE) dispatchLater(3_000)
        return updated
    }

    override suspend fun myIncidents(): List<Incident> {
        delay(300)
        return synchronized(lock) {
            val me = current()
            incidents
                .filter {
                    when (me.role) {
                        Role.REQUESTER -> requesterOf[it.id] == me.id
                        Role.RESPONDER, Role.DRIVER -> it.assignedTo == me.id
                        Role.DISPATCHER -> true
                    }
                }
                .sortedByDescending { it.id }
        }
    }

    override suspend fun allIncidents(): List<Incident> {
        delay(250)
        return synchronized(lock) {
            if (current().role != Role.DISPATCHER) throw httpError(403, "Only dispatchers can view the full queue.")
            incidents.sortedByDescending { it.id }
        }
    }

    override suspend fun units(): List<User> {
        delay(250)
        return synchronized(lock) {
            if (current().role != Role.DISPATCHER) throw httpError(403, "Only dispatchers can view field units.")
            accounts.map { it.user }.filter { it.role == Role.RESPONDER || it.role == Role.DRIVER }
        }
    }

    override suspend fun activeResponderLocations(): List<ResponderLocation> {
        delay(150)
        return synchronized(lock) {
            if (current().role != Role.DISPATCHER) throw httpError(403, "Only dispatchers can view responder locations.")
            // The fake backend never fabricates GPS points.
            emptyList()
        }
    }

    override suspend fun createIncident(body: CreateIncidentRequest): Incident {
        delay(600)
        val created = synchronized(lock) {
            val me = current()
            if (me.role != Role.REQUESTER) {
                throw httpError(403, "Only requesters can send emergency requests.")
            }
            val incident = Incident(
                id = nextIncidentId++,
                type = body.type,
                description = body.description,
                latitude = body.latitude,
                longitude = body.longitude,
                accuracyM = body.accuracyM,
                locationNotes = body.locationNotes,
                locationRecordedAt = body.locationRecordedAt,
                locationSource = body.locationSource,
                status = IncidentStatus.PENDING,
                requesterName = me.name,
                requesterPhone = me.phone,
                assignedTo = null,
                createdAt = Instant.now().toString(),
            )
            incidents += incident
            requesterOf[incident.id] = me.id
            incident
        }
        dispatchLater(5_000)
        return created
    }

    override suspend fun updateIncidentStatus(id: Long, body: UpdateStatusRequest): Incident {
        delay(300)
        val updated = synchronized(lock) {
            val me = current()
            val index = incidents.indexOfFirst { it.id == id }
            if (index < 0) throw httpError(404, "Incident not found.")
            val incident = incidents[index]
            if (incident.assignedTo != me.id) {
                throw httpError(403, "This incident is not assigned to you.")
            }
            if (body.status !in incident.status.nextSteps()) {
                throw httpError(422, "Cannot change status from ${incident.status.label} to ${body.status.label}.")
            }
            val result = incident.copy(status = body.status)
            incidents[index] = result
            val duty = when (body.status) {
                IncidentStatus.EN_ROUTE -> DutyStatus.EN_ROUTE
                IncidentStatus.ON_SCENE -> DutyStatus.ON_SCENE
                IncidentStatus.TRANSPORTING -> DutyStatus.TRANSPORTING
                IncidentStatus.RESOLVED -> DutyStatus.AVAILABLE
                else -> null
            }
            if (duty != null) setDuty(me.id, duty)
            result
        }
        if (updated.status == IncidentStatus.RESOLVED) dispatchLater(3_000)
        return updated
    }

    override suspend fun assignIncident(id: Long, body: AssignIncidentRequest): Incident {
        delay(300)
        return synchronized(lock) {
            if (current().role != Role.DISPATCHER) throw httpError(403, "Only dispatchers can assign incidents.")
            val index = incidents.indexOfFirst { it.id == id }
            if (index < 0) throw httpError(404, "Incident not found.")
            val unit = accounts.firstOrNull { it.user.id == body.userId }?.user
                ?: throw httpError(404, "Unit not found.")
            if (unit.role != Role.RESPONDER && unit.role != Role.DRIVER) {
                throw httpError(422, "Only responders and drivers can be assigned.")
            }
            if (unit.dutyStatus != DutyStatus.AVAILABLE || hasActiveIncident(unit.id)) {
                throw httpError(422, "That unit is not available.")
            }
            val incident = incidents[index]
            if (incident.status != IncidentStatus.PENDING) {
                throw httpError(422, "Only pending incidents can be assigned.")
            }
            incident.copy(status = IncidentStatus.ASSIGNED, assignedTo = unit.id).also {
                incidents[index] = it
            }
        }
    }

    override suspend fun recordLocation(body: ResponderLocationRequest): Long {
        delay(100)
        return synchronized(lock) {
            val me = current()
            val incident = incidents.firstOrNull { it.id == body.incidentId }
                ?: throw httpError(404, "Incident not found.")
            if (me.role != Role.RESPONDER && me.role != Role.DRIVER || incident.assignedTo != me.id) {
                throw httpError(403, "This incident is not assigned to you.")
            }
            if (incident.status != IncidentStatus.EN_ROUTE) {
                throw httpError(422, "Location sharing is only available while en route.")
            }
            if (body.latitude !in -90.0..90.0 || body.longitude !in -180.0..180.0) {
                throw httpError(422, "Location coordinates are invalid.")
            }
            1L
        }
    }

    // ---- helpers (call only while holding `lock`, except dispatchLater/httpError) ----

    private fun issueToken(userId: Long): String =
        UUID.randomUUID().toString().also { tokens[it] = userId }

    private fun current(): User {
        val id = tokenProvider()?.let { tokens[it] } ?: throw httpError(401, "Unauthenticated.")
        return accounts.firstOrNull { it.user.id == id }?.user ?: throw httpError(401, "Unauthenticated.")
    }

    private fun setDuty(userId: Long, status: DutyStatus) {
        val index = accounts.indexOfFirst { it.user.id == userId }
        if (index >= 0) {
            val account = accounts[index]
            accounts[index] = account.copy(user = account.user.copy(dutyStatus = status))
        }
    }

    private fun hasActiveIncident(userId: Long): Boolean =
        incidents.any { it.assignedTo == userId && it.status.isActive }

    /** Pretends to be the dispatcher: gives the oldest pending incident to a free, available unit. */
    private fun dispatchLater(delayMs: Long) {
        if (!autoDispatchEnabled) return
        scope.launch {
            delay(delayMs)
            synchronized(lock) {
                val pendingIndex = incidents.indexOfFirst { it.status == IncidentStatus.PENDING }
                if (pendingIndex < 0) return@synchronized
                val unit = accounts.map { it.user }.firstOrNull {
                    (it.role == Role.RESPONDER || it.role == Role.DRIVER) &&
                        it.dutyStatus == DutyStatus.AVAILABLE &&
                        !hasActiveIncident(it.id)
                } ?: return@synchronized
                incidents[pendingIndex] = incidents[pendingIndex].copy(
                    status = IncidentStatus.ASSIGNED,
                    assignedTo = unit.id,
                )
            }
        }
    }

    private fun httpError(code: Int, message: String): HttpException {
        val json = """{"message":"$message"}"""
        val body = json.toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Any>(code, body))
    }
}
