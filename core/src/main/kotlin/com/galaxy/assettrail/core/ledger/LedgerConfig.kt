package com.galaxy.assettrail.core.ledger

import org.stellar.sdk.Asset
import org.stellar.sdk.Network

/** Settings for building transactions on a network, used by the simulated platform. */
data class LedgerConfig(
    val network: Network,
    val usdcIssuer: String,
    /** Inner-transaction base fee in stroops. The fee account pays the real fee through a fee bump. */
    val baseFee: Long = 100,
) {
    val usdc: Asset get() = Asset.createNonNativeAsset("USDC", usdcIssuer)

    /** What the platform reports for this network and [contractId]. */
    fun networkInfo(contractId: String) = NetworkInfo(
        networkPassphrase = network.networkPassphrase,
        contractId = contractId,
        payAsset = "USDC:$usdcIssuer",
    )

    companion object {
        /** Circle's USDC issuer on the Stellar testnet. */
        const val TESTNET_USDC_ISSUER = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"

        val Testnet = LedgerConfig(network = Network.TESTNET, usdcIssuer = TESTNET_USDC_ISSUER)
    }
}
