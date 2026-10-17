package com.galaxy.assettrail.driver.ui.trip

import com.galaxy.assettrail.core.driver.ActiveTrip
import com.galaxy.assettrail.core.driver.CheckInOutcome
import com.galaxy.assettrail.core.driver.CheckInRequest
import com.galaxy.assettrail.core.driver.DriverState
import com.galaxy.assettrail.core.driver.LegPayStatus
import com.galaxy.assettrail.core.driver.isAwaitingSignature
import com.galaxy.assettrail.core.driver.nextWaypoint
import com.galaxy.assettrail.core.geo.Geo
import com.galaxy.assettrail.core.geo.GpsFix
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.driver.AppContainer
import com.galaxy.assettrail.driver.BuildConfig
import com.galaxy.assettrail.driver.ui.ActionViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TripViewModel(private val container: AppContainer) : ActionViewModel() {
    private val repository = container.repository

    val state: StateFlow<DriverState> = repository.state
    val online: StateFlow<Boolean> = container.online
    val fix: StateFlow<GpsFix?> = container.location.fix
    val isDemo = container.isDemo
    val demoTools = container.demoTools
    val productName = container.productName

    private val trip: ActiveTrip? get() = state.value.trip

    private val _progress = MutableStateFlow<String?>(null)

    /** What the autopilot is waiting for while a step records on the ledger; null when idle. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private suspend fun <T> working(text: String, block: suspend () -> T): T {
        _progress.value = text
        try {
            return block()
        } finally {
            _progress.value = null
        }
    }

    fun accept(tripId: String, onDone: () -> Unit) = action(onDone) {
        repository.acceptOffer(tripId)
        // Demo: a newly accepted trip starts with the driver at the pickup.
        if (demoTools && !container.location.hasSimulatedFix()) {
            repository.state.value.trip?.plan?.pickup?.let { container.location.simulateNear(it, 120.0) }
        }
    }

    /**
     * Demo autopilot: every tap moves the journey one step. Accept the offer, drive to the next stop
     * (the GPS trigger), check in there (a real check-in, recorded and paid on the ledger), and at the
     * end have the receiver sign, which releases the final leg.
     */
    fun autopilot() = action {
        val state = repository.state.value
        val trip = state.trip
        when {
            trip == null -> {
                val offer = state.offers.firstOrNull()
                if (offer == null) {
                    repository.refresh()
                    say("No trip offered yet. Tap again in a moment.")
                } else {
                    working("Accepting ${offer.id}…") { repository.acceptOffer(offer.id) }
                    say("Accepted ${offer.id}: ${offer.routeLabel}. Tap to check in at ${offer.pickup.name}.")
                }
            }
            trip.closed -> repository.finishTrip()
            else -> {
                val next = trip.nextWaypoint()
                if (next == null) {
                    if (!trip.isAwaitingSignature()) {
                        repository.refresh()
                        return@action
                    }
                    working("${trip.plan.receiverName} is signing for the load. Recording the delivery on Stellar…") {
                        container.demo?.receiverSigns(trip.plan.id)
                        repository.refresh()
                    }
                    say("Signed. The contract paid the final leg to your wallet.")
                    return@action
                }
                val position = fix.value
                val inside = position != null && Geo.distanceM(position.point, next.position) <= next.geofenceRadiusM
                if (!inside) {
                    container.location.simulateNear(next, metres = 82.0)
                    say("Arrived at ${next.name}: inside the ${next.geofenceRadiusM} m geofence. Tap to check in.")
                    return@action
                }
                val photos = if (next.type == WaypointType.PICKUP) listOf(container.photos.samplePhotoHash()) else emptyList()
                val tag = trip.plan.asset.tagId.ifBlank { BuildConfig.DEMO_TAG_ID }
                val outcome = working("Checking in at ${next.name}: signed on this phone, recording on Stellar…") {
                    repository.checkIn(CheckInRequest(next.index, tag, position!!, photos))
                }
                say(
                    when (outcome) {
                        is CheckInOutcome.Recorded -> {
                            val tx = outcome.checkIn.txHash.orEmpty().take(8)
                            when (outcome.checkIn.pay) {
                                LegPayStatus.PAID -> "${next.name} recorded on Stellar (tx $tx…). +${outcome.checkIn.legPay.display()} USDC paid."
                                LegPayStatus.IN_CONTRACT -> "Arrival recorded (tx $tx…). Tap to have the receiver sign."
                                else -> "${next.name} recorded on Stellar (tx $tx…). Custody started."
                            }
                        }
                        is CheckInOutcome.Saved -> "Saved on the phone; it records when the signal is back."
                        is CheckInOutcome.Failed -> outcome.checkIn.failureMessage ?: "The platform refused the check-in."
                        is CheckInOutcome.Blocked -> outcome.message
                    },
                )
            }
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
