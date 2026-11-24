package io.github.spysyweeb.beamline.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.location.PositionSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Positions from Android's own LocationManager (no Google Play services): the satellite provider,
 * plus the network provider where the phone has one. Updates once a second while collected; the
 * app collects only while it is in the foreground. Starts with the last known fix, so the puck
 * appears at once (grey if that fix is old).
 */
class AndroidPositionSource(private val context: Context) : PositionSource {

    /**
     * The newest location seen, for the compass's magnetic declination and the sun rule. Written by
     * the listener (on the main looper: requestLocationUpdates below) and when the flow starts (its
     * collector's thread), read from the main thread and other collectors: @Volatile (batch B).
     */
    @Volatile
    var lastLocation: Location? = null
        private set

    /**
     * Test extras only (batch C2, rule G7): every fix reports this accuracy radius instead of its
     * own, so the emulator (whose `geo fix` has no accuracy) can play a poor indoor fix. Set by
     * MainActivity's `--ef fix_accuracy_m <m>` (0 = off) only when BuildConfig.TEST_EXTRAS.
     */
    @Volatile
    var testAccuracyM: Float? = null

    /** Precise location: the only grant that lets guidance start (an approximate fix is never a `gps` fix, G1). */
    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in hasPermission(); a SecurityException ends the flow
    override fun fixes(): Flow<Fix> = callbackFlow {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null || !hasPermission()) {
            close()
            return@callbackFlow
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in lm.allProviders }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
                trySend(location.toFix())
            }

            // Needed below API 30, where these are abstract.
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }?.let {
                lastLocation = it
                trySend(it.toFix())
            }
            for (p in providers) lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            Log.i(TAG, "location updates from ${providers.joinToString()} (location enabled: ${LocationManagerCompat.isLocationEnabled(lm)})") // log-ok: provider names (gps, network) and a boolean
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission withdrawn: ${e.javaClass.simpleName}") // log-ok: an exception's class name
            close()
        }
        awaitClose { lm.removeUpdates(listener) }
    }

    /**
     * Where the phone was last (the newest fix seen, else the system's last known location), for
     * the sun rule of the map style Automatic; null without a location permission or a location.
     * Never logged (PV2).
     */
    @SuppressLint("MissingPermission") // checked in hasPermission()
    fun lastKnownPoint(): GeoPoint? {
        lastLocation?.let { return GeoPoint(it.latitude, it.longitude) }
        if (!hasPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in lm.allProviders }
                .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { GeoPoint(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            null
        }
    }

    fun locationEnabled(): Boolean =
        context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

    private fun Location.toFix() = Fix(
        lat = latitude,
        lon = longitude,
        accuracyM = testAccuracyM ?: if (hasAccuracy()) accuracy else null,
        elapsedMs = elapsedRealtimeNanos / 1_000_000,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        provider = provider ?: "",
    )

    private companion object {
        const val TAG = "BeamlineLocation"
    }
}
