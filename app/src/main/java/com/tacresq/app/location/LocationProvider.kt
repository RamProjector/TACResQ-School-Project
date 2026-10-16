package com.tacresq.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.tacresq.app.data.model.GeoFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

class LocationProvider(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    companion object {
        private const val MAX_FIX_AGE_NANOS = 2L * 60L * 1_000_000_000L
    }

    fun hasPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** A recent location fix (at most two minutes old), or null if none is available. */
    @SuppressLint("MissingPermission")
    suspend fun current(): GeoFix? {
        if (!hasPermission()) return null
        val location = try {
            val current = try {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            current?.takeIf { it.isRecent() }
                ?: client.lastLocation.await()?.takeIf { it.isRecent() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return location?.let { GeoFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null) }
    }

    private fun Location.isRecent(): Boolean {
        val ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
        return ageNanos in 0L..MAX_FIX_AGE_NANOS
    }
}
