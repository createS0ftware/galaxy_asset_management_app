package com.galaxy.assettrail.driver.device

import com.galaxy.assettrail.core.platform.PlatformException
import com.galaxy.assettrail.driver.BuildConfig
import com.galaxy.assettrail.http.HttpPlatformApi
import com.galaxy.assettrail.mock.DemoControls
import com.galaxy.assettrail.mock.DemoFlags

/**
 * Debuggable live builds: plays the receiver against the real backend, so a walkthrough can reach
 * final acceptance and payment on the ledger. Ops-side switches only exist in the in-app demo platform.
 */
class LiveDemoControls(private val api: HttpPlatformApi) : DemoControls {

    override suspend fun receiverSigns(tripId: String) = api.demoReceiverSigns(tripId, BuildConfig.DEMO_TAG_ID)

    override suspend fun receiverReportsProblem(tripId: String) = demoPlatformOnly()

    override suspend fun opsResolvesDisputeForDriver(tripId: String) = demoPlatformOnly()

    override suspend fun expireDeliveryDeadline(tripId: String) = demoPlatformOnly()

    override suspend fun flags(): DemoFlags = DemoFlags()

    override suspend fun updateFlags(change: (DemoFlags) -> DemoFlags) = Unit

    override suspend fun reset() = Unit

    private fun demoPlatformOnly(): Nothing =
        throw PlatformException("DEMO_PLATFORM_ONLY", "Only the in-app demo platform can do this.")
}
