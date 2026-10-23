package com.tacresq.app.data.remote

import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.ResponderAssignmentStatus
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
                        Role.RESPONDER, Role.DRIVER -> it.assignedTo == me.id || me.id in it.assignedUserIds
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
            incidents.firstOrNull { requesterOf[it.id] == me.id && it.status.isActive }?.let { active ->
                throw httpError(422, "You already have active request #${active.id}. Cancel it while pending or contact dispatch if a unit is responding.")
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

    override suspend fun cancelMyIncident(id: Long): Incident {
        delay(150)
        return synchronized(lock) {
            val me = current()
            if (me.role != Role.REQUESTER) throw httpError(403, "Only requesters can cancel their own requests.")
            val index = incidents.indexOfFirst { it.id == id && requesterOf[it.id] == me.id }
            if (index < 0) throw httpError(404, "Request not found.")
            val incident = incidents[index]
            if (incident.status != IncidentStatus.PENDING) {
                throw httpError(422, "A unit may already be responding. Contact dispatch to cancel this request.")
            }
            incident.copy(status = IncidentStatus.CANCELLED).also { incidents[index] = it }
        }
    }

    override suspend fun updateMyIncident(body: UpdateMyIncidentRequest): Incident {
        delay(150)
        return synchronized(lock) {
            val me = current()
            if (me.role != Role.REQUESTER) throw httpError(403, "Only requesters can edit their own requests.")
            val index = incidents.indexOfFirst { it.id == body.id && requesterOf[it.id] == me.id }
            if (index < 0) throw httpError(404, "Request not found.")
            val incident = incidents[index]
            if (incident.status != IncidentStatus.PENDING) {
                throw httpError(422, "This request can only be edited before dispatch assignment. Contact dispatch for urgent corrections.")
            }
            val now = Instant.now()
            if (incident.requesterEditCount > 0) {
                val last = incident.requesterLastEditedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                if (last != null && now.isBefore(last.plusSeconds(60))) {
                    val seconds = java.time.Duration.between(now, last.plusSeconds(60)).seconds.coerceAtLeast(1)
                    throw httpError(422, "You can edit this request again in " + seconds + " seconds.")
                }
                if (!body.confirmReview) throw httpError(422, "Review the updated emergency details and confirm before saving another edit.")
            }
            val updated = incident.copy(
                type = com.tacresq.app.data.model.IncidentType.entries.firstOrNull { it.name.equals(body.type, ignoreCase = true) }
                    ?: throw httpError(422, "Choose a valid emergency type."),
                description = body.description?.trim()?.takeIf { it.isNotEmpty() },
                latitude = body.latitude,
                longitude = body.longitude,
                accuracyM = body.accuracyM,
                locationNotes = body.locationNotes?.trim()?.takeIf { it.isNotEmpty() },
                locationRecordedAt = body.locationRecordedAt,
                locationSource = body.locationSource,
                requesterEditCount = incident.requesterEditCount + 1,
                requesterLastEditedAt = now.toString(),
            )
            incidents[index] = updated
            updated
        }
    }

    override suspend fun updateIncidentStatus(id: Long, body: UpdateStatusRequest): Incident {
        delay(300)
        val updated = synchronized(lock) {
            val me = current()
            val index = incidents.indexOfFirst { it.id == id }
            if (index < 0) throw httpError(404, "Incident not found.")
            val incident = incidents[index]
            if (incident.assignedTo != me.id && me.id !in incident.assignedUserIds) {
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
            if (duty != null) incident.assignedUserIds.ifEmpty { listOfNotNull(incident.assignedTo) }.forEach { setDuty(it, duty) }
            result
        }
        if (updated.status == IncidentStatus.RESOLVED) dispatchLater(3_000)
        return updated
    }


    override suspend fun updateMyAssignmentStatus(id: Long, body: UpdateStatusRequest): Incident {
        delay(300)
        val updated = synchronized(lock) {
            val me = current()
            if (me.role != Role.RESPONDER && me.role != Role.DRIVER) {
                throw httpError(403, "Only assigned field units can update their response status.")
            }
            val index = incidents.indexOfFirst { it.id == id }
            if (index < 0) throw httpError(404, "Incident not found.")
            val incident = incidents[index]
            val teamIds = incident.assignedUserIds.ifEmpty { listOfNotNull(incident.assignedTo) }
            if (me.id !in teamIds) throw httpError(403, "This incident is not assigned to your unit.")
            if (incident.status == IncidentStatus.RESOLVED || incident.status == IncidentStatus.CANCELLED) {
                throw httpError(422, "A closed incident cannot accept further unit status updates.")
            }
            val existingStatuses = incident.assignedUserStatuses.ifEmpty {
                teamIds.map { unitId -> ResponderAssignmentStatus(unitId, incident.status) }
            }
            val own = existingStatuses.firstOrNull { it.userId == me.id }
                ?: throw httpError(403, "Your unit assignment could not be verified.")
            if (body.status !in own.status.nextSteps()) {
                throw httpError(422, "Cannot change your unit status from ${own.status.label} to ${body.status.label}.")
            }
            val timestamp = Instant.now().toString()
            val nextStatuses = existingStatuses.map {
                if (it.userId == me.id) it.copy(status = body.status, statusUpdatedAt = timestamp) else it
            }
            val statuses = nextStatuses.map { it.status }
            val aggregate = when {
                statuses.all { it == IncidentStatus.RESOLVED } -> IncidentStatus.RESOLVED
                statuses.all { it in listOf(IncidentStatus.ON_SCENE, IncidentStatus.TRANSPORTING, IncidentStatus.RESOLVED) } &&
                    statuses.any { it == IncidentStatus.TRANSPORTING } -> IncidentStatus.TRANSPORTING
                statuses.all { it in listOf(IncidentStatus.ON_SCENE, IncidentStatus.TRANSPORTING, IncidentStatus.RESOLVED) } -> IncidentStatus.ON_SCENE
                statuses.any { it in listOf(IncidentStatus.EN_ROUTE, IncidentStatus.ON_SCENE, IncidentStatus.TRANSPORTING, IncidentStatus.RESOLVED) } -> IncidentStatus.EN_ROUTE
                else -> IncidentStatus.ASSIGNED
            }
            val result = incident.copy(status = aggregate, assignedUserStatuses = nextStatuses)
            incidents[index] = result
            val duty = when (body.status) {
                IncidentStatus.ASSIGNED -> DutyStatus.ASSIGNED
                IncidentStatus.EN_ROUTE -> DutyStatus.EN_ROUTE
                IncidentStatus.ON_SCENE -> DutyStatus.ON_SCENE
                IncidentStatus.TRANSPORTING -> DutyStatus.TRANSPORTING
                IncidentStatus.RESOLVED -> DutyStatus.RESOLVED
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
            incident.copy(
                status = IncidentStatus.ASSIGNED,
                assignedTo = unit.id,
                assignedUserIds = listOf(unit.id),
                assignedUserStatuses = listOf(ResponderAssignmentStatus(unit.id, IncidentStatus.ASSIGNED, Instant.now().toString())),
            ).also {
                incidents[index] = it
            }
        }
    }

    override suspend fun assignIncidentTeam(id: Long, body: AssignIncidentTeamRequest): Incident {
        delay(300)
        return synchronized(lock) {
            if (current().role != Role.DISPATCHER) throw httpError(403, "Only dispatchers can assign incidents.")
            if (body.userIds.isEmpty() || body.userIds.size > 5 || body.userIds.distinct().size != body.userIds.size) {
                throw httpError(422, "Select between 1 and 5 different field units.")
            }
            val index = incidents.indexOfFirst { it.id == id }
            if (index < 0) throw httpError(404, "Incident not found.")
            val incident = incidents[index]
            if (incident.status !in listOf(IncidentStatus.PENDING, IncidentStatus.ASSIGNED, IncidentStatus.EN_ROUTE)) {
                throw httpError(422, "A response team can only be assigned or expanded before the incident reaches the scene.")
            }
            val currentTeam = incident.assignedUserIds.ifEmpty { listOfNotNull(incident.assignedTo) }
            if (currentTeam.size + body.userIds.size > 5) throw httpError(422, "A response team may contain at most 5 field units.")
            val units = body.userIds.map { id ->
                accounts.firstOrNull { it.user.id == id }?.user ?: throw httpError(404, "A selected unit was not found.")
            }
            if (units.any { (it.role != Role.RESPONDER && it.role != Role.DRIVER) || it.dutyStatus != DutyStatus.AVAILABLE || hasActiveIncident(it.id) }) {
                throw httpError(422, "One or more selected units are no longer available. Refresh and choose again.")
            }
            units.forEach { setDuty(it.id, DutyStatus.ASSIGNED) }
            val newTeam = (currentTeam + body.userIds).distinct()
            val oldStatuses = incident.assignedUserStatuses.ifEmpty {
                currentTeam.map { unitId -> ResponderAssignmentStatus(unitId, incident.status, incident.createdAt) }
            }
            val newStatuses = oldStatuses + units.map { unit ->
                ResponderAssignmentStatus(unit.id, IncidentStatus.ASSIGNED, Instant.now().toString())
            }
            incident.copy(
                status = if (incident.status == IncidentStatus.PENDING) IncidentStatus.ASSIGNED else incident.status,
                assignedTo = incident.assignedTo ?: units.first().id,
                assignedUserIds = newTeam,
                assignedUserStatuses = newStatuses.distinctBy { it.userId },
            ).also {
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
            if (me.role != Role.RESPONDER && me.role != Role.DRIVER || incident.assignedTo != me.id && me.id !in incident.assignedUserIds) {
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
        incidents.any { (it.assignedTo == userId || userId in it.assignedUserIds) && it.status.isActive }

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
