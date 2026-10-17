package com.galaxy.assettrail.core.ledger

import kotlinx.serialization.Serializable
import org.stellar.sdk.Asset
import org.stellar.sdk.Network

/** The network and contract the phone signs for, as the platform reports them (backend `GET /v1/network`). */
@Serializable
data class NetworkInfo(
    val networkPassphrase: String,
    /** The Asset Trail contract: escrow, custody records and per-asset hash chains. */
    val contractId: String,
    /** The pay asset as `CODE:ISSUER`, the platform's USDC. */
    val payAsset: String,
    /** The ledger close time the platform plans with (backend: 5 s). */
    val ledgerSeconds: Int = DEFAULT_LEDGER_SECONDS,
    /** [N hours] (spec §4.4): how long a check-in saved offline can still record (backend default 12). */
    val maxOfflineHours: Int = DEFAULT_MAX_OFFLINE_HOURS,
) {
    val network: Network get() = Network(networkPassphrase)

    val stellarAsset: Asset
        get() {
            val (code, issuer) = payAsset.split(':', limit = 2)
            return Asset.createNonNativeAsset(code, issuer)
        }

    companion object {
        const val DEFAULT_LEDGER_SECONDS = 5
        const val DEFAULT_MAX_OFFLINE_HOURS = 12
    }
}

/** The latest ledger the phone saw, and when, so it can date authorizations while offline. */
@Serializable
data class LedgerClock(val latestLedger: Long, val seenAt: Long) {

    /** The ledger the network has probably reached at [now]. */
    fun estimate(now: Long, ledgerSeconds: Int): Long = latestLedger + maxOf(0L, (now - seenAt) / 1000 / ledgerSeconds)

    /**
     * The last ledger a check-in signed at [now] stays valid in: about [NetworkInfo.maxOfflineHours]
     * from now, as the backend's phone client computes it (`offlineValidUntil`).
     */
    fun offlineValidUntil(now: Long, network: NetworkInfo): Long =
        estimate(now, network.ledgerSeconds) + network.maxOfflineHours * 3_600L / network.ledgerSeconds
}
