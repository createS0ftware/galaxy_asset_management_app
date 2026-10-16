package com.galaxy.assettrail.core.ledger

import org.stellar.sdk.Asset
import org.stellar.sdk.Network
import java.util.concurrent.TimeUnit

data class LedgerConfig(
    val network: Network,
    val usdcIssuer: String,
    /** Inner-transaction base fee in stroops. The fee account pays the real fee through a fee bump. */
    val baseFee: Long = 100,
    /** [N hours] in the spec (§4.4): an offline check-in older than this needs an ops override. */
    val offlineMaxAgeMillis: Long = TimeUnit.HOURS.toMillis(6),
) {
    val usdc: Asset get() = Asset.createNonNativeAsset("USDC", usdcIssuer)

    companion object {
        /** Circle's USDC issuer on the Stellar testnet. */
        const val TESTNET_USDC_ISSUER = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"

        val Testnet = LedgerConfig(network = Network.TESTNET, usdcIssuer = TESTNET_USDC_ISSUER)
    }
}
