package com.tacresq.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.tacresq.app.data.model.GeoFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

sealed interface LocationCaptureResult {
    data class Success(val fix: GeoFix) : LocationCaptureResult
    data object PermissionDenied : LocationCaptureResult
    data object ServicesDisabled : LocationCaptureResult
    data object TimedOut : LocationCaptureResult
    data object InvalidFix : LocationCaptureResult
    data object Unavailable : LocationCaptureResult
}

/** One-time requester fixes and an explicitly collected foreground responder stream. */
class LocationProvider(context: Context) {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    companion object {
        private const val FIX_TIMEOUT_MS = 18_000L
        private const val REQUEST_DURATION_MS = 15_000L
        private const val MAX_RETURNED_FIX_AGE_NANOS = 60_000_000_000L
        private const val MAX_SEND_FIX_AGE_NANOS = 120_000_000_000L
        private const val RESPONDER_INTERVAL_MS = 12_000L
        private const val RESPONDER_MIN_INTERVAL_MS = 10_000L
        private const val RESPONDER_MIN_DISTANCE_M = 20f
    }

    fun hasPermission(): Boolean = hasPrecisePermission() || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasApproximatePermission(): Boolean = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPrecisePermission(): Boolean = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    fun isFresh(fix: GeoFix): Boolean {
        val ageNanos = SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos
        return ageNanos in 0L..MAX_SEND_FIX_AGE_NANOS
    }

    /** Obtain one current fix; deliberately does not fall back to Location.lastLocation. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(): LocationCaptureResult {
        if (!hasPermission()) return LocationCaptureResult.PermissionDenied
        if (!isLocationEnabled()) return LocationCaptureResult.ServicesDisabled

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(REQUEST_DURATION_MS)
            .build()
        val cancellation = CancellationTokenSource()
        val location = try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                client.getCurrentLocation(request, cancellation.token).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            return LocationCaptureResult.PermissionDenied
        } catch (_: Exception) {
            return LocationCaptureResult.Unavailable
        } finally {
            cancellation.cancel()
        } ?: return LocationCaptureResult.TimedOut

        if (!location.isValidCurrentFix()) return LocationCaptureResult.InvalidFix
        val accuracy = location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() && it >= 0f }
        return LocationCaptureResult.Success(
            GeoFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyM = accuracy,
                capturedAtEpochMillis = location.time,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                hasPrecisePermission = hasPrecisePermission(),
            )
        )
    }

    /**
     * Emit in-use approximate fixes only. Explicit COARSE granularity avoids using the fine
     * permission reserved by this source pack's Android Location Button flow. Collection
     * cancellation unregisters the fused-location callback; callers must bind collection to
     * the visible responder lifecycle and never collect from an application/background scope.
    */
    @OptIn(ExperimentalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    fun responderForegroundUpdates(): Flow<LocationCaptureResult> = callbackFlow {
        if (!hasApproximatePermission()) {
            trySend(LocationCaptureResult.PermissionDenied)
            close()
            return@callbackFlow
        }
        if (!isLocationEnabled()) {
            trySend(LocationCaptureResult.ServicesDisabled)
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            RESPONDER_INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(RESPONDER_MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(RESPONDER_MIN_DISTANCE_M)
            .setGranularity(Granularity.GRANULARITY_COARSE)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation
                if (location == null || !location.isValidCurrentFix()) {
                    trySend(LocationCaptureResult.InvalidFix)
                } else {
                    val accuracy = location.accuracy.takeIf {
                        location.hasAccuracy() && it.isFinite() && it >= 0f
                    }
                    trySend(LocationCaptureResult.Success(location.toGeoFix(accuracy, precisePermission = false)))
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper()).await()
            awaitClose()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            trySend(LocationCaptureResult.PermissionDenied)
            close()
        } catch (_: Exception) {
            trySend(LocationCaptureResult.Unavailable)
            close()
        } finally {
            client.removeLocationUpdates(callback)
        }
    }

    private fun isLocationEnabled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(false)

    private fun Location.isValidCurrentFix(): Boolean {
        val ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
        return latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            time > 0L && elapsedRealtimeNanos > 0L &&
            ageNanos in 0L..MAX_RETURNED_FIX_AGE_NANOS
    }

    private fun Location.toGeoFix(accuracy: Float?, precisePermission: Boolean) = GeoFix(
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracy,
        capturedAtEpochMillis = time,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        hasPrecisePermission = precisePermission,
    )
}
