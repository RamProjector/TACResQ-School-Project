package com.tacresq.app.data.repo

import com.tacresq.app.data.model.UserNotification

/** Narrow, testable contract for the persistent in-app notification actions. */
interface NotificationRepository {
    suspend fun myNotifications(limit: Int = 50): Result<List<UserNotification>>
    suspend fun markNotificationRead(notificationId: Long): Result<Boolean>
}
