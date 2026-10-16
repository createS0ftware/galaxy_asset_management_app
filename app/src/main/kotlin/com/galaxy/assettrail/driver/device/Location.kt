package com.galaxy.assettrail.driver.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.galaxy.assettrail.core.geo.Geo
import com.galaxy.assettrail.core.geo.GeoPoint
import com.galaxy.assettrail.core.geo.GpsFix
import com.galaxy.assettrail.core.model.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

/** Where the phone is. Demo builds start on a simulated position the demo controls can move. */
class LocationController(context: Context, scope: CoroutineScope, demo: Boolean) {

    enum class Mode { SIMULATED, DEVICE }

    private val device = DeviceLocation(context)
    private val simulated = MutableStateFlow<GpsFix?>(null)

    private val _mode = MutableStateFlow(if (demo) Mode.SIMULATED else Mode.DEVICE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val fix: StateFlow<GpsFix?> = _mode
        .flatMapLatest { if (it == Mode.SIMULATED) simulated else device.fix }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val hasPermission: Boolean get() = device.hasPermission()

    fun setMode(mode: Mode) {
        _mode.value = mode
        if (mode == Mode.DEVICE) device.start() else device.stop()
    }

    /** Re-tries device updates after the permission is granted. */
    fun onPermissionResult() {
        if (_mode.value == Mode.DEVICE) device.start()
    }

    /** Demo: stand [metres] from a waypoint's centre. */
    fun simulateNear(waypoint: Waypoint, metres: Double, bearing: Double = 225.0) {
        moveTo(Geo.offset(waypoint.position, metres, bearing))
    }

    /** Demo: somewhere on the road, [fraction] of the way from [from] to [to]. */
    fun simulateBetween(from: GeoPoint, to: GeoPoint, fraction: Double) {
        val total = Geo.distanceM(from, to)
        moveTo(Geo.offset(from, total * fraction, Geo.bearingDeg(from, to)))
    }

    fun hasSimulatedFix(): Boolean = simulated.value != null

    private fun moveTo(point: GeoPoint) {
        simulated.value = GpsFix(point, accuracyM = 8, timeMillis = System.currentTimeMillis())
    }
}

private class DeviceLocation(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix.asStateFlow()
    private var listening = false

    private val listener = LocationListener { location -> _fix.value = location.toFix() }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (listening || !hasPermission()) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        providers.forEach { provider ->
            manager.getLastKnownLocation(provider)?.let { _fix.value = it.toFix() }
            manager.requestLocationUpdates(provider, 3_000L, 0f, listener, Looper.getMainLooper())
        }
        listening = providers.isNotEmpty()
    }

    fun stop() {
        if (!listening) return
        manager.removeUpdates(listener)
        listening = false
    }

    private fun Location.toFix() = GpsFix(
        point = GeoPoint(latitude, longitude),
        accuracyM = accuracy.roundToInt(),
        timeMillis = time,
        // Spec §9: mock locations can't be used for check-ins.
        isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider,
    )
}
