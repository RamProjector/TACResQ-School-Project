package com.tacresq.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.tacresq.app.data.model.GeoFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

class LocationProvider(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** A fresh GPS fix, or null if permission is missing or no location is available. */
    @SuppressLint("MissingPermission")
    suspend fun current(): GeoFix? {
        if (!hasPermission()) return null
        val location = try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
                ?: client.lastLocation.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return location?.let {
            GeoFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null)
        }
    }
}
