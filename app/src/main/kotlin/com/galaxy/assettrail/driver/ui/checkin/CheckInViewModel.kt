package com.galaxy.assettrail.driver.ui.checkin

import androidx.lifecycle.viewModelScope
import com.galaxy.assettrail.core.driver.BlockReason
import com.galaxy.assettrail.core.driver.CheckInOutcome
import com.galaxy.assettrail.core.driver.CheckInRequest
import com.galaxy.assettrail.core.model.TripPlan
import com.galaxy.assettrail.core.model.Waypoint
import com.galaxy.assettrail.core.model.WaypointType
import com.galaxy.assettrail.driver.AppContainer
import com.galaxy.assettrail.driver.ui.ActionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Check-in at one waypoint: scan the tag, photograph the load at pickup, then sign and record. */
class CheckInViewModel(private val container: AppContainer, private val waypointIndex: Int) : ActionViewModel() {

    enum class Step { SCAN, PHOTO, SUBMITTING, BLOCKED, DONE }

    data class Ui(
        val step: Step = Step.SCAN,
        val tag: String? = null,
        val photoHash: String? = null,
        val blockedTitle: String = "",
        val blockedMessage: String = "",
        val canScanAgain: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    val plan: TripPlan? = container.repository.state.value.trip?.plan
    val waypoint: Waypoint? = plan?.waypoints?.getOrNull(waypointIndex)
    val online: StateFlow<Boolean> = container.online
    val isDemo = container.isDemo
    val nfcAvailable: Boolean get() = container.nfc.isAvailable
    val nfcEnabled: Boolean get() = container.nfc.isEnabled

    init {
        container.nfc.setListening(true)
        viewModelScope.launch { container.nfc.tags.collect(::onTagScanned) }
    }

    private fun onTagScanned(tag: String) {
        if (_ui.value.step != Step.SCAN) return
        container.nfc.setListening(false)
        val matches = tag.equals(plan?.asset?.tagId, ignoreCase = true)
        _ui.value = _ui.value.copy(tag = tag)
        if (matches && waypoint?.type == WaypointType.PICKUP) {
            _ui.value = _ui.value.copy(step = Step.PHOTO)
        } else {
            submit()
        }
    }

    /** Demo: act as the right tag, or as a tag from some other asset. */
    fun demoScan(correct: Boolean) {
        val tag = if (correct) plan?.asset?.tagId ?: return else "04:A3:7E:00"
        container.nfc.simulateScan(tag)
    }

    fun onPhotoTaken(file: File) {
        viewModelScope.launch {
            val hash = container.photos.hash(file)
            if (hash == null) say("The photo wasn't saved. Try again.") else _ui.value = _ui.value.copy(photoHash = hash)
        }
    }

    fun useSamplePhoto() {
        _ui.value = _ui.value.copy(photoHash = container.photos.samplePhotoHash())
    }

    fun clearPhoto() {
        _ui.value = _ui.value.copy(photoHash = null)
    }

    fun confirmPhoto() = submit()

    fun scanAgain() {
        _ui.value = Ui()
        container.nfc.setListening(true)
    }

    private fun submit() {
        val tag = _ui.value.tag ?: return
        val fix = container.location.fix.value
        if (fix == null) {
            block("No location yet", "Wait a moment for GPS, then scan again.", canScanAgain = true)
            return
        }
        _ui.value = _ui.value.copy(step = Step.SUBMITTING)
        viewModelScope.launch {
            val outcome = try {
                container.repository.checkIn(CheckInRequest(waypointIndex, tag, fix, listOfNotNull(_ui.value.photoHash)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                block("Couldn't save the check-in", friendly(e), canScanAgain = true)
                return@launch
            }
            if (outcome is CheckInOutcome.Blocked) {
                val title = when (outcome.reason) {
                    BlockReason.TAG_MISMATCH -> "Tag doesn't match"
                    BlockReason.OUTSIDE_GEOFENCE -> "Outside the geofence"
                    BlockReason.MOCK_LOCATION -> "Mock location detected"
                    BlockReason.NOT_NEXT -> "Not your next stop"
                    BlockReason.NO_TRIP -> "No active trip"
                }
                block(title, outcome.message, canScanAgain = outcome.reason == BlockReason.TAG_MISMATCH)
            } else {
                _ui.value = _ui.value.copy(step = Step.DONE)
            }
        }
    }

    private fun block(title: String, message: String, canScanAgain: Boolean) {
        _ui.value = _ui.value.copy(step = Step.BLOCKED, blockedTitle = title, blockedMessage = message, canScanAgain = canScanAgain)
    }

    override fun onCleared() {
        container.nfc.setListening(false)
    }
}
