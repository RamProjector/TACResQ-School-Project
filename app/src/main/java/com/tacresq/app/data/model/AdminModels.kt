package com.tacresq.app.data.model

import com.google.gson.annotations.SerializedName

/** Bounded administrator directory response returned by api_admin_list_users_page. */
data class AdminUserRecord(
    val id: Long,
    val name: String,
    val email: String,
    val phone: String?,
    val role: String,
    @SerializedName("duty_status") val dutyStatus: String?,
    @SerializedName("created_at") val createdAt: String?,
)

data class AdminUserPage(
    val users: List<AdminUserRecord>,
    @SerializedName("total_count") val totalCount: Int,
)

data class AdminAuditEvent(
    val id: Long,
    @SerializedName("actor_user_id") val actorUserId: Long?,
    @SerializedName("actor_identity") val actorIdentity: String,
    @SerializedName("target_user_id") val targetUserId: Long?,
    val action: String,
    @SerializedName("old_role") val oldRole: String?,
    @SerializedName("new_role") val newRole: String?,
    val reason: String?,
    @SerializedName("created_at") val createdAt: String?,
)

data class AdminRoleChangeResult(
    val id: Long,
    val name: String,
    val email: String,
    @SerializedName("old_role") val oldRole: String,
    val role: String,
    @SerializedName("duty_status") val dutyStatus: String?,
)
