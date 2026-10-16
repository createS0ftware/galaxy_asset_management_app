package com.galaxy.assettrail.core.driver

import com.galaxy.assettrail.core.ledger.DriverSigner
import com.galaxy.assettrail.core.ledger.KeyPairSigner
import org.stellar.sdk.KeyPair

/**
 * The driver's wallet key (D). It is created on the phone and never leaves it: only the public
 * address goes to the platform. On Android the seed is wrapped by a hardware-backed Keystore key.
 */
interface DriverKeyStore {
    /** The wallet address, or null before onboarding. */
    fun accountId(): String?

    /** Creates the key and returns its address. */
    fun create(): String

    fun signer(): DriverSigner

    fun delete()
}

/** Keeps the key in memory. For tests and the JVM; the app uses the Keystore-backed one. */
class InMemoryDriverKeyStore(private var keyPair: KeyPair? = null) : DriverKeyStore {
    override fun accountId(): String? = keyPair?.accountId

    override fun create(): String = KeyPair.random().also { keyPair = it }.accountId

    override fun signer(): DriverSigner = KeyPairSigner(keyPair ?: error("No wallet key on this phone"))

    override fun delete() {
        keyPair = null
    }
}
