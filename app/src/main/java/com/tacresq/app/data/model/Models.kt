package com.tacresq.app.data.model

import com.google.gson.annotations.SerializedName

// Note: Gson does not enforce Kotlin nullability. If the server omits a field that is
// declared non-null here, it arrives as null at runtime. Keep docs/API.md and these
// classes in sync.

enum class Role(val label: String) {
    @SerializedName("requester") REQUESTER("Emergency requester"),
    @SerializedName("responder") RESPONDER("Field responder"),
    @SerializedName("driver") DRIVER("Driver"),
    @SerializedName("dispatcher") DISPATCHER("Dispatcher"),
}

enum class DutyStatus(val label: String) {
    @SerializedName("off_duty") OFF_DUTY("Off duty"),
    @SerializedName("available") AVAILABLE("Available"),
    // Computed for active response-team members; not a user-set DB value.
    @SerializedName("assigned") ASSIGNED("Assigned"),
    @SerializedName("en_route") EN_ROUTE("En route"),
    @SerializedName("on_scene") ON_SCENE("On scene"),
    @SerializedName("transporting") TRANSPORTING("Transporting"),
}

enum class IncidentType(val label: String) {
    @SerializedName("medical") MEDICAL("Medical"),
    @SerializedName("fire") FIRE("Fire"),
    @SerializedName("vehicular") VEHICULAR("Vehicular accident"),
    @SerializedName("disaster") DISASTER("Flood or disaster"),
    @SerializedName("other") OTHER("Other"),
}

enum class IncidentStatus(val label: String) {
    @SerializedName("pending") PENDING("Waiting for dispatch"),
    @SerializedName("assigned") ASSIGNED("Unit assigned"),
    @SerializedName("en_route") EN_ROUTE("Unit en route"),
    @SerializedName("on_scene") ON_SCENE("Unit on scene"),
    @SerializedName("transporting") TRANSPORTING("Transporting"),
    @SerializedName("resolved") RESOLVED("Resolved"),
    @SerializedName("cancelled") CANCELLED("Cancelled");

    val isActive: Boolean
        get() = this != RESOLVED && this != CANCELLED
}

/** The statuses a unit may move an incident to next. The server must enforce the same rules. */
fun IncidentStatus.nextSteps(): List<IncidentStatus> = when (this) {
    IncidentStatus.ASSIGNED -> listOf(IncidentStatus.EN_ROUTE)
    IncidentStatus.EN_ROUTE -> listOf(IncidentStatus.ON_SCENE)
    IncidentStatus.ON_SCENE -> listOf(IncidentStatus.TRANSPORTING, IncidentStatus.RESOLVED)
    IncidentStatus.TRANSPORTING -> listOf(IncidentStatus.RESOLVED)
    else -> emptyList()
}

data class User(
    val id: Long,
    val name: String,
    val email: String,
    val phone: String? = null,
    val role: Role,
    @SerializedName("duty_status") val dutyStatus: DutyStatus? = null,
)

data class ResponderAssignmentStatus(
    @SerializedName("user_id") val userId: Long,
    val status: IncidentStatus,
    @SerializedName("status_updated_at") val statusUpdatedAt: String? = null,
)

data class Incident(
    val id: Long,
    val type: IncidentType,
    val description: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerializedName("accuracy_m") val accuracyM: Float? = null,
    @SerializedName("location_notes") val locationNotes: String? = null,
    @SerializedName("location_recorded_at") val locationRecordedAt: String? = null,
    val status: IncidentStatus,
    @SerializedName("requester_name") val requesterName: String? = null,
    @SerializedName("requester_phone") val requesterPhone: String? = null,
    @SerializedName("assigned_to") val assignedTo: Long? = null,
    @SerializedName("assigned_user_ids") val assignedUserIds: List<Long> = emptyList(),
    @SerializedName("assigned_user_statuses") val assignedUserStatuses: List<ResponderAssignmentStatus> = emptyList(),
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("location_source") val locationSource: String? = null,
    @SerializedName("requester_edit_count") val requesterEditCount: Int = 0,
    @SerializedName("requester_last_edited_at") val requesterLastEditedAt: String? = null,
)

data class Session(val token: String, val user: User)

data class ResponderLocation(
    @SerializedName("incident_id") val incidentId: Long,
    @SerializedName("user_id") val userId: Long,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("accuracy_m") val accuracyM: Float? = null,
    @SerializedName("recorded_at") val recordedAt: String,
)

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val session: Session) : SessionState
}

/** A single, user-requested GPS reading; elapsed time is used only to reject stale fixes locally. */
data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val capturedAtEpochMillis: Long,
    val elapsedRealtimeNanos: Long,
    val hasPrecisePermission: Boolean,
    val manuallySelected: Boolean = false,
)
