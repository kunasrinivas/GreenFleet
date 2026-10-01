package com.greenfleet.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.greenfleet.app.ui.*
import com.greenfleet.domain.Coordinate
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val model: FleetViewModel by viewModels()
    private var locationJob: Job? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locateDepot() else model.error("Location permission was declined. You can enter the depot address manually.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GreenFleetApp(model, onLocation = {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locateDepot()
            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }, onNavigate = ::navigate) }
    }
    private fun locateDepot() {
        locationJob?.cancel()
        locationJob = lifecycleScope.launch {
            try {
                val point = withTimeout(20000) { currentLocation() }
                model.edit { it.copy(source = "${point.lat},${point.lng}") }
            } catch (_: TimeoutCancellationException) { model.error("Could not locate the depot. Enter its address or try outdoors.") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { model.error("Location is unavailable. Enable foreground location or enter the depot manually.") }
        }
    }
    @Suppress("MissingPermission")
    private suspend fun currentLocation(): Coordinate = suspendCancellableCoroutine { continuation ->
        val manager = getSystemService(LocationManager::class.java)
        val precise = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val provider = when {
            precise && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> throw IllegalStateException("No foreground location provider.")
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.accuracy > 250 || android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 60_000_000_000L) return
                manager.removeUpdates(this)
                if (continuation.isActive) continuation.resume(Coordinate(location.latitude, location.longitude))
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
    }
    private fun navigate(coordinate: Coordinate) {
        val point = "${coordinate.lat},${coordinate.lng}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$point&mode=d")).setPackage("com.google.android.apps.maps"))
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$point&travelmode=driving&dir_action=navigate")))
            } catch (_: android.content.ActivityNotFoundException) { model.error("Install Google Maps or a browser to start navigation.") }
        }
    }
    override fun onStop() { locationJob?.cancel(); super.onStop() }
}

