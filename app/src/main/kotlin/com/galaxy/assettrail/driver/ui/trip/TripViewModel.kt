package com.galaxy.assettrail.driver.ui.trip

import com.galaxy.assettrail.core.driver.ActiveTrip
import com.galaxy.assettrail.core.driver.DriverState
import com.galaxy.assettrail.core.driver.nextWaypoint
import com.galaxy.assettrail.core.geo.GpsFix
import com.galaxy.assettrail.driver.AppContainer
import com.galaxy.assettrail.driver.ui.ActionViewModel
import kotlinx.coroutines.flow.StateFlow

class TripViewModel(private val container: AppContainer) : ActionViewModel() {
    private val repository = container.repository

    val state: StateFlow<DriverState> = repository.state
    val online: StateFlow<Boolean> = container.online
    val fix: StateFlow<GpsFix?> = container.location.fix
    val isDemo = container.isDemo
    val productName = container.productName

    private val trip: ActiveTrip? get() = state.value.trip

    fun accept(tripId: String, onDone: () -> Unit) = action(onDone) {
        repository.acceptOffer(tripId)
        // Demo: a newly accepted trip starts with the driver at the pickup.
        if (isDemo && !container.location.hasSimulatedFix()) {
            repository.state.value.trip?.plan?.pickup?.let { container.location.simulateNear(it, 120.0) }
        }
    }

    fun claimFinalPay() = action {
        repository.claimFinalPay()
        say("Final pay released to your wallet.")
    }

    fun finishTrip() = action { repository.finishTrip() }

    // Demo controls for the simulated position.

    fun demoArrive() {
        trip?.nextWaypoint()?.let { container.location.simulateNear(it, metres = 82.0) }
    }

    fun demoJustOutside() {
        trip?.nextWaypoint()?.let { container.location.simulateNear(it, metres = it.geofenceRadiusM + 160.0) }
    }

    fun demoOnTheRoad() {
        val current = trip ?: return
        val next = current.nextWaypoint() ?: return
        val from = current.plan.waypoints.getOrNull(next.index - 1)?.position ?: fix.value?.point ?: return
        container.location.simulateBetween(from, next.position, fraction = 0.55)
    }
}
