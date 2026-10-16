package com.galaxy.assettrail.driver

import android.app.Application
import com.galaxy.assettrail.core.driver.DriverRepository
import com.galaxy.assettrail.core.driver.DriverState
import com.galaxy.assettrail.core.driver.nextWaypoint
import com.galaxy.assettrail.core.ledger.LedgerConfig
import com.galaxy.assettrail.core.platform.PlatformApi
import com.galaxy.assettrail.core.store.FileStateStore
import com.galaxy.assettrail.driver.device.AndroidKeyVault
import com.galaxy.assettrail.driver.device.ConnectivityMonitor
import com.galaxy.assettrail.driver.device.LocationController
import com.galaxy.assettrail.driver.device.NfcTagReader
import com.galaxy.assettrail.driver.device.PhotoStore
import com.galaxy.assettrail.mock.DemoControls
import com.galaxy.assettrail.mock.MockPlatform
import com.galaxy.assettrail.mock.SimState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock

/** Wires the app together. One per process, owned by [AssetTrailApp]. */
class AppContainer(app: Application) {
    val isDemo: Boolean = BuildConfig.DEMO
    val productName: String = app.getString(R.string.product_name)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: Clock = Clock.systemDefaultZone()
    val ledger: LedgerConfig = LedgerConfig.Testnet

    private val connectivity = ConnectivityMonitor(app)

    /** Demo switch: pretend there's no signal, to walk through offline check-ins. */
    val simulatedNoSignal = MutableStateFlow(false)

    val online: StateFlow<Boolean> = combine(connectivity.connected, simulatedNoSignal) { connected, noSignal ->
        connected && !noSignal
    }.stateIn(scope, SharingStarted.Eagerly, connectivity.connected.value)

    val keyVault = AndroidKeyVault(app)

    // The backend and escrow-contract API are another team's. Until theirs is wired in, this
    // in-process stand-in answers for it; replace it with their client here.
    private val mockPlatform = MockPlatform(
        store = FileStateStore(File(app.filesDir, "mock-platform.json"), SimState.serializer()),
        config = ledger,
        clock = clock,
        isOnline = { online.value },
        productName = productName,
    )
    val platform: PlatformApi = mockPlatform
    val demo: DemoControls? = mockPlatform.takeIf { isDemo }

    val repository = DriverRepository(
        platform = platform,
        store = FileStateStore(File(app.filesDir, "driver-state.json"), DriverState.serializer()),
        keys = keyVault,
        ledger = ledger,
        online = online,
        clock = clock,
        scope = scope,
    )

    val location = LocationController(app, scope, demo = isDemo)
    val nfc = NfcTagReader(app)
    val photos = PhotoStore(app)

    init {
        scope.launch {
            repository.load()
            repository.startSync()
            // Demo: start the simulated driver at their next stop.
            repository.state.collect { placeSimulatedDriver(it) }
        }
    }

    private fun placeSimulatedDriver(state: DriverState) {
        if (!isDemo || location.hasSimulatedFix()) return
        val next = state.trip?.nextWaypoint() ?: return
        location.simulateNear(next, metres = 82.0)
    }

    suspend fun resetDemo() {
        repository.resetEverything()
        demo?.reset()
        simulatedNoSignal.value = false
    }
}
