package com.tacresq.app.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tacresq.app.R
import com.tacresq.app.TacResqApp
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.statusFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Observable status for the user-facing responder screen; the service remains the tracking owner. */
data class ResponderTrackingStatus(
    val incidentId: Long? = null,
    val active: Boolean = false,
    val starting: Boolean = false,
    val lastSentAtEpochMillis: Long? = null,
    val lastAccuracyMeters: Float? = null,
    val message: String? = null,
)

/** App-scoped commands and status for the explicit, user-started location foreground service. */
class ResponderLocationTrackingController(context: Context) {
    private val appContext = context.applicationContext
    private val _status = MutableStateFlow(ResponderTrackingStatus())
    val status: StateFlow<ResponderTrackingStatus> = _status.asStateFlow()

    fun start(incidentId: Long): Result<Unit> {
        require(incidentId > 0L)
        val intent = Intent(appContext, ResponderLocationTrackingService::class.java)
            .setAction(ResponderLocationTrackingService.ACTION_START)
            .putExtra(ResponderLocationTrackingService.EXTRA_INCIDENT_ID, incidentId)
        return try {
            ContextCompat.startForegroundService(appContext, intent)
            _status.value = ResponderTrackingStatus(
                incidentId = incidentId,
                starting = true,
                message = "Starting location sharing…",
            )
            Result.success(Unit)
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    fun stop(message: String = "Location sharing stopped.") {
        val intent = Intent(appContext, ResponderLocationTrackingService::class.java)
            .setAction(ResponderLocationTrackingService.ACTION_STOP)
        runCatching { appContext.startService(intent) }
        _status.value = ResponderTrackingStatus(message = message)
    }

    internal fun serviceStarted(incidentId: Long) {
        val previous = _status.value.takeIf { it.incidentId == incidentId }
        _status.value = ResponderTrackingStatus(
            incidentId = incidentId,
            active = true,
            lastSentAtEpochMillis = previous?.lastSentAtEpochMillis,
            lastAccuracyMeters = previous?.lastAccuracyMeters,
            message = "Sharing approximate location with authorized dispatch while this response is en route.",
        )
    }

    internal fun locationSent(incidentId: Long, sentAtEpochMillis: Long, accuracyMeters: Float?) {
        if (_status.value.incidentId != incidentId) return
        _status.value = _status.value.copy(
            active = true,
            starting = false,
            lastSentAtEpochMillis = sentAtEpochMillis,
            lastAccuracyMeters = accuracyMeters,
            message = null,
        )
    }

    internal fun serviceMessage(incidentId: Long, message: String) {
        if (_status.value.incidentId == incidentId) {
            _status.value = _status.value.copy(active = true, starting = false, message = message)
        }
    }

    internal fun serviceStopped(message: String) {
        _status.value = ResponderTrackingStatus(message = message)
    }
}

/**
 * Explicitly started only after a responder chooses to share location. The service owns the
 * fused-location callback and sends coarse fixes through the existing assignment-checked RPC.
 */
class ResponderLocationTrackingService : Service() {
    companion object {
        const val ACTION_START = "com.tacresq.app.location.START_RESPONDER_SHARING"
        const val ACTION_STOP = "com.tacresq.app.location.STOP_RESPONDER_SHARING"
        const val EXTRA_INCIDENT_ID = "incident_id"
        private const val NOTIFICATION_CHANNEL_ID = "responder_location_sharing"
        private const val NOTIFICATION_ID = 2401
        private const val RETRY_DELAY_MILLIS = 12_000L

        fun hasRequiredPermissions(context: Context): Boolean {
            val coarseGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val notificationsGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            return coarseGranted && notificationsGranted
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeIncidentId: Long? = null
    private var trackingJob: Job? = null
    private var assignmentMonitorJob: Job? = null
    private lateinit var controller: ResponderLocationTrackingController

    override fun onCreate() {
        super.onCreate()
        controller = (application as TacResqApp).container.responderLocationTracking
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopActiveSharing("Location sharing stopped from the notification.")
                stopSelf(startId)
            }
            ACTION_START -> {
                val incidentId = intent.getLongExtra(EXTRA_INCIDENT_ID, -1L)
                if (incidentId <= 0L) {
                    stopActiveSharing("Location sharing could not start because the assignment was invalid.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (activeIncidentId != null) {
                    if (activeIncidentId == incidentId) {
                        controller.serviceStarted(incidentId)
                    } else {
                        controller.serviceMessage(
                            activeIncidentId!!,
                            "Another response is already sharing location. Stop it before starting a different assignment.",
                        )
                    }
                    return START_NOT_STICKY
                }
                if (!ResponderLocationTrackingService.hasRequiredPermissions(this)) {
                    controller.serviceStopped("Allow approximate location and notifications before sharing during a response.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        createNotification(),
                        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
                    )
                } catch (failure: Exception) {
                    controller.serviceStopped("Location sharing could not start. Return to TACResQ and resume sharing.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                activeIncidentId = incidentId
                controller.serviceStarted(incidentId)
                trackingJob = serviceScope.launch { trackAssignment(incidentId) }
                assignmentMonitorJob = serviceScope.launch { monitorAssignment(incidentId) }
            }
            else -> {
                stopActiveSharing("Location sharing stopped because the service request was invalid.")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun trackAssignment(incidentId: Long) {
        val appContainer = (application as TacResqApp).container
        val repository = appContainer.repository
        val locationProvider = appContainer.locationProvider
        try {
            while (activeIncidentId == incidentId) {
                val userResult = repository.me()
                val user = userResult.getOrNull()
                if (user == null) {
                    val failure = userResult.exceptionOrNull()
                    if (failure is HttpException && failure.code() in setOf(401, 403)) {
                        stopActiveSharing("Location sharing stopped because the responder session is no longer authorized.")
                        stopSelf()
                        return
                    }
                    controller.serviceMessage(incidentId, "Could not verify the active responder session; location updates are paused.")
                    delay(RETRY_DELAY_MILLIS)
                    continue
                }
                val assignmentsResult = repository.myIncidents()
                val assignments = assignmentsResult.getOrNull()
                if (assignments == null) {
                    val failure = assignmentsResult.exceptionOrNull()
                    if (failure is HttpException && failure.code() in setOf(401, 403)) {
                        stopActiveSharing("Location sharing stopped because this responder session is no longer authorized.")
                        stopSelf()
                        return
                    }
                    controller.serviceMessage(incidentId, "Could not verify the active assignment; location updates are paused.")
                    delay(RETRY_DELAY_MILLIS)
                    continue
                }
                val assignmentIsActive = assignments.any {
                    it.id == incidentId && it.statusFor(user.id) == IncidentStatus.EN_ROUTE
                }
                if (!assignmentIsActive) {
                    stopActiveSharing("Location sharing stopped because this assignment is no longer en route.")
                    stopSelf()
                    return
                }
                if (!ResponderLocationTrackingService.hasRequiredPermissions(this) ||
                    !locationProvider.hasApproximatePermission()
                ) {
                    stopActiveSharing("Approximate location permission was withdrawn; no further location will be sent.")
                    stopSelf()
                    return
                }
                locationProvider.responderForegroundUpdates().collect { capture ->
                    if (activeIncidentId != incidentId) return@collect
                    when (capture) {
                        is LocationCaptureResult.Success -> {
                            if (!locationProvider.isFresh(capture.fix)) {
                                controller.serviceMessage(incidentId, "Waiting for a fresh location fix; no stale position was sent.")
                            } else {
                                val result = repository.recordResponderLocation(incidentId, capture.fix)
                                val failure = result.exceptionOrNull()
                                if (failure == null) {
                                    controller.locationSent(
                                        incidentId,
                                        System.currentTimeMillis(),
                                        capture.fix.accuracyM,
                                    )
                                } else if (failure is HttpException && failure.code() in setOf(401, 403, 404, 422)) {
                                    stopActiveSharing("Location sharing stopped because the session or assignment is no longer authorized.")
                                    stopSelf()
                                    return@collect
                                } else {
                                    controller.serviceMessage(incidentId, "Latest location could not be sent; TACResQ will retry when connectivity returns.")
                                }
                            }
                        }
                        LocationCaptureResult.PermissionDenied -> {
                            stopActiveSharing("Approximate location permission was withdrawn; no further location will be sent.")
                            stopSelf()
                            return@collect
                        }
                        LocationCaptureResult.ServicesDisabled -> {
                            stopActiveSharing("Location services are off. Re-enable location and explicitly resume sharing.")
                            stopSelf()
                            return@collect
                        }
                        LocationCaptureResult.TimedOut,
                        LocationCaptureResult.InvalidFix,
                        LocationCaptureResult.Unavailable,
                        -> controller.serviceMessage(incidentId, "Waiting for a valid current location; no stale position was sent.")
                    }
                }
                if (activeIncidentId == incidentId) delay(RETRY_DELAY_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            stopActiveSharing("Location permission is no longer available; no further location will be sent.")
            stopSelf()
        } catch (_: Exception) {
            if (activeIncidentId == incidentId) {
                controller.serviceMessage(incidentId, "Location sharing paused after an unexpected error. Return to TACResQ and resume.")
                stopActiveSharing("Location sharing stopped after an unexpected error.")
                stopSelf()
            }
        }
    }

    /** Re-check consent scope and assignment state even if the device produces no new fix. */
    private suspend fun monitorAssignment(incidentId: Long) {
        val repository = (application as TacResqApp).container.repository
        try {
            while (activeIncidentId == incidentId) {
                delay(30_000L)
                if (activeIncidentId != incidentId) return
                if (!ResponderLocationTrackingService.hasRequiredPermissions(this)) {
                    stopActiveSharing("Location or notification permission was withdrawn; sharing has stopped.")
                    stopSelf()
                    return
                }
                val userResult = repository.me()
                val user = userResult.getOrNull()
                if (user == null) {
                    val failure = userResult.exceptionOrNull()
                    if (failure is HttpException && failure.code() in setOf(401, 403)) {
                        stopActiveSharing("Location sharing stopped because the responder session is no longer authorized.")
                        stopSelf()
                        return
                    }
                    controller.serviceMessage(incidentId, "Could not refresh assignment status; every location write remains server-authorized.")
                    continue
                }
                val assignmentsResult = repository.myIncidents()
                val assignments = assignmentsResult.getOrNull()
                if (assignments == null) {
                    val failure = assignmentsResult.exceptionOrNull()
                    if (failure is HttpException && failure.code() in setOf(401, 403)) {
                        stopActiveSharing("Location sharing stopped because this responder session is no longer authorized.")
                        stopSelf()
                        return
                    }
                    controller.serviceMessage(incidentId, "Could not refresh assignment status; every location write remains server-authorized.")
                    continue
                }
                if (assignments.none { it.id == incidentId && it.statusFor(user.id) == IncidentStatus.EN_ROUTE }) {
                    stopActiveSharing("Location sharing stopped because this assignment is no longer en route.")
                    stopSelf()
                    return
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (activeIncidentId == incidentId) {
                stopActiveSharing("Location sharing stopped because the active assignment could not be verified.")
                stopSelf()
            }
        }
    }

    private fun stopActiveSharing(message: String) {
        val oldIncidentId = activeIncidentId
        activeIncidentId = null
        trackingJob?.cancel()
        trackingJob = null
        assignmentMonitorJob?.cancel()
        assignmentMonitorJob = null
        if (::controller.isInitialized) controller.serviceStopped(message)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (oldIncidentId == null) stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Responder location sharing",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows when TACResQ is sharing a responder's location with authorized dispatch."
                    setShowBadge(false)
                },
            )
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ResponderLocationTrackingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TACResQ location sharing is active")
            .setContentText("Authorized dispatch can see your recent position for this en-route response.")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop sharing", stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val hadActiveTracking = activeIncidentId != null
        trackingJob?.cancel()
        trackingJob = null
        assignmentMonitorJob?.cancel()
        assignmentMonitorJob = null
        activeIncidentId = null
        serviceScope.cancel()
        if (hadActiveTracking && ::controller.isInitialized) {
            controller.serviceStopped("Location sharing stopped because Android ended the tracking service. Resume sharing in TACResQ.")
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not stop here: the user may still be on an active response and the ongoing
        // foreground-service notification remains available as an explicit stop control.
        super.onTaskRemoved(rootIntent)
    }
}
