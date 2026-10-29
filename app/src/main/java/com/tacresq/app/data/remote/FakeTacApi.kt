package com.tacresq.app.data.remote

import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.ResponderAssignmentStatus
import com.tacresq.app.data.model.User
import com.tacresq.app.data.model.UserNotification
import com.tacresq.app.data.model.IncidentMessage
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
) : TacApi, TacNotificationApi, TacChatApi {

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
    private data class StoredNotification(val recipientId: Long, val value: UserNotification)
    private data class StoredMessage(val incidentId: Long, val senderId: Long, val clientMessageId: String, val value: IncidentMessage)
    private val notifications = mutableListOf<StoredNotification>()
    private val messages = mutableListOf<StoredMessage>()
    private val messageSendTimes = mutableMapOf<Long, MutableList<Long>>()
    private var nextNotificationId = 1L
    private var nextMessageId = 1L
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
                        Role.ADMIN -> false
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

    override suspend fun myNotifications(limit: Int): List<UserNotification> {
        delay(120)
        if (limit !in 1..100) throw httpError(422, "Notification page size must be between 1 and 100.")
        return synchronized(lock) {
            val me = current()
            notifications.asSequence()
                .filter { it.recipientId == me.id }
                .map { it.value }
                .sortedWith(compareByDescending<UserNotification> { it.createdAt.orEmpty() }.thenByDescending { it.id })
                .take(limit)
                .toList()
        }
    }

    override suspend fun markNotificationRead(notificationId: Long): Boolean {
        delay(100)
        if (notificationId < 1) throw httpError(422, "Notification ID must be positive.")
        return synchronized(lock) {
            val me = current()
            val index = notifications.indexOfFirst { it.value.id == notificationId && it.recipientId == me.id }
            if (index < 0) return@synchronized false
            val stored = notifications[index]
            notifications[index] = stored.copy(
                value = stored.value.copy(readAt = stored.value.readAt ?: Instant.now().toString()),
            )
            true
        }
    }

    override suspend fun myIncidentMessages(incidentId: Long, limit: Int, beforeId: Long?): List<IncidentMessage> {
        delay(120)
        if (limit !in 1..100) throw httpError(422, "Message page size must be between 1 and 100.")
        if (incidentId < 1 || (beforeId != null && beforeId < 1)) throw httpError(422, "Incident ID or message cursor is invalid.")
        return synchronized(lock) {
            val me = current()
            ensureChatAccess(incidentId, me)
            messages.asSequence()
                .filter { it.incidentId == incidentId && (beforeId == null || it.value.id < beforeId) }
                .sortedByDescending { it.value.id }
                .take(limit)
                .map { it.value }
                .toList()
                .sortedBy { it.id }
        }
    }

    override suspend fun sendIncidentMessage(incidentId: Long, message: String, clientMessageId: String): IncidentMessage {
        delay(120)
        val normalized = message.trim()
        if (incidentId < 1 || normalized.length !in 1..2000) {
            throw httpError(422, "Message must contain 1 to 2000 characters and use a valid incident ID.")
        }
        val normalizedClientId = try {
            UUID.fromString(clientMessageId).toString()
        } catch (_: IllegalArgumentException) {
            throw httpError(422, "A valid client message ID is required.")
        }

        return synchronized(lock) {
            val me = current()
            val incident = ensureChatAccess(incidentId, me)
            val existing = messages.firstOrNull {
                it.senderId == me.id && it.clientMessageId == normalizedClientId
            }
            if (existing != null) {
                if (existing.incidentId != incidentId || existing.value.message != normalized) {
                    throw httpError(409, "Client message ID was already used for different content.")
                }
                return@synchronized existing.value
            }

            val nowMs = System.currentTimeMillis()
            val recentTimes = messageSendTimes.getOrPut(me.id) { mutableListOf() }
            recentTimes.removeAll { it < nowMs - 60_000 }
            if (recentTimes.size >= 20) throw httpError(429, "Message rate limit reached. Wait before sending again.")

            val created = IncidentMessage(
                id = nextMessageId++,
                incidentId = incidentId,
                senderId = me.id,
                message = normalized,
                createdAt = Instant.now().toString(),
            )
            messages += StoredMessage(incidentId, me.id, normalizedClientId, created)
            recentTimes += nowMs

            val recipients = (
                listOfNotNull(requesterOf[incidentId]) +
                    incident.assignedUserIds.ifEmpty { listOfNotNull(incident.assignedTo) }
                ).distinct().filterNot { it == me.id }
            recipients.forEach { recipientId ->
                addNotification(
                    recipientId, incidentId, "incident_message",
                    "New incident message",
                    "A participant sent a new message in an incident conversation.",
                )
            }
            created
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

    override suspend fun updateIncidentStatus(id: Long, body: UpdateStatusRequest): Incident =
        updateMyAssignmentStatus(id, body)

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
            if (result.status != incident.status) {
                emitStatusNotifications(result, me.id)
            }
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
                emitAssignmentNotifications(id, listOf(unit.id))
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
                emitAssignmentNotifications(id, body.userIds)
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

    private fun ensureChatAccess(incidentId: Long, user: User): Incident {
        val incident = incidents.firstOrNull { it.id == incidentId }
            ?: throw httpError(404, "Incident not found.")
        val allowed = when (user.role) {
            Role.DISPATCHER -> true
            Role.REQUESTER -> requesterOf[incidentId] == user.id
            Role.RESPONDER, Role.DRIVER ->
                user.id in incident.assignedUserIds || incident.assignedTo == user.id
            else -> false
        }
        if (!allowed) throw httpError(403, "Incident chat is not available to this account.")
        return incident
    }

    private fun emitAssignmentNotifications(incidentId: Long, assignedUserIds: List<Long>) {
        val requesterId = requesterOf[incidentId] ?: return
        assignedUserIds.distinct().forEach { recipientId ->
            addNotification(
                recipientId, incidentId, "incident_assigned",
                "New incident assignment",
                "You have been assigned to an incident. Open TACResQ to review the details.",
            )
        }
        addNotification(
            requesterId, incidentId, "incident_assigned",
            "Response unit assigned",
            "A response unit has been assigned to your request.",
        )
    }

    private fun emitStatusNotifications(incident: Incident, actorId: Long) {
        val requesterId = requesterOf[incident.id] ?: return
        val recipients = (listOf(requesterId) + incident.assignedUserIds.ifEmpty { listOfNotNull(incident.assignedTo) })
            .distinct()
            .filterNot { it == actorId }
        recipients.forEach { recipientId ->
            addNotification(
                recipientId, incident.id, "incident_status_changed",
                "Incident status updated",
                "The incident status is now ${incident.status.name.lowercase().replace('_', ' ')}.",
            )
        }
    }

    private fun addNotification(
        recipientId: Long,
        incidentId: Long,
        eventType: String,
        title: String,
        body: String,
    ) {
        if (eventType == "incident_assigned" && notifications.any {
                it.recipientId == recipientId &&
                    it.value.incidentId == incidentId &&
                    it.value.eventType == eventType &&
                    it.value.readAt == null
            }
        ) return
        notifications += StoredNotification(
            recipientId = recipientId,
            value = UserNotification(
                id = nextNotificationId++,
                incidentId = incidentId,
                eventType = eventType,
                title = title,
                body = body,
                createdAt = Instant.now().toString(),
                readAt = null,
            ),
        )
    }

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
