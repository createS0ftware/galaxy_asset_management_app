package com.galaxy.assettrail.core.ledger

import org.stellar.sdk.Address
import org.stellar.sdk.Auth
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType
import org.stellar.sdk.xdr.SorobanAuthorizationEntry
import org.stellar.sdk.xdr.SorobanAuthorizedInvocation

/**
 * Soroban authorization entries: how the driver signs a contract call (backend README, "How a
 * check-in works"). The fee account is the transaction source, so no driver sequence number is in
 * play. Each entry carries a random nonce, which is why check-ins signed offline never block each
 * other, and a last valid ledger, which stands in for the spec's `maxTime`.
 */
object AuthEntries {

    /** Signs [invocation] for [signer]'s address, valid through ledger [validUntilLedger]. Returns base64 XDR. */
    fun authorize(signer: DriverSigner, invocation: SorobanAuthorizedInvocation, validUntilLedger: Long, network: Network): String {
        val sign = Auth.Signer { preimage ->
            Auth.defaultAccountSignatureScVal(signer.accountId, signer.sign(Auth.authorizationPayloadHash(preimage)))
        }
        return Auth.authorizeInvocation(sign, signer.accountId, validUntilLedger, invocation, network).toXdrBase64()
    }

    /** What an entry claims: who signed it, through which ledger, for which call. */
    class Inspected(
        val address: String,
        val validUntilLedger: Long,
        val invocation: SorobanAuthorizedInvocation,
        val entry: SorobanAuthorizationEntry,
    )

    fun inspect(entryXdr: String): Inspected {
        val entry = SorobanAuthorizationEntry.fromXdrBase64(entryXdr)
        val credentials = Auth.getAddressCredentials(entry.credentials)
            ?: throw IllegalArgumentException("The entry has no address credentials")
        return Inspected(
            address = Address.fromSCAddress(credentials.address).encodedAddress,
            validUntilLedger = credentials.signatureExpirationLedger.uint32.number,
            invocation = entry.rootInvocation,
            entry = entry,
        )
    }

    /**
     * The signer service's checks (backend `TrailContract.checkEntry`), plus the ed25519 signature
     * itself, which on the network the host checks. Returns why the entry is refused, or null.
     */
    fun problem(
        entryXdr: String,
        expectedSigner: String,
        expected: SorobanAuthorizedInvocation,
        latestLedger: Long,
        network: Network,
    ): String? {
        val inspected = runCatching { inspect(entryXdr) }.getOrElse { return "entry can't be read" }
        return when {
            inspected.address != expectedSigner -> "entry is signed for ${inspected.address}, expected $expectedSigner"
            inspected.validUntilLedger <= latestLedger -> "entry has expired"
            inspected.invocation.toXdrBase64() != expected.toXdrBase64() -> "entry does not authorize this call"
            !hasValidSignature(inspected, network) -> "entry is not signed by $expectedSigner"
            else -> null
        }
    }

    private fun hasValidSignature(inspected: Inspected, network: Network): Boolean = runCatching {
        val credentials = Auth.getAddressCredentials(inspected.entry.credentials) ?: return false
        val preimage = Auth.buildAuthorizationEntryPreimage(inspected.entry, inspected.validUntilLedger, network)
        val payload = Auth.authorizationPayloadHash(preimage)
        val key = KeyPair.fromAccountId(inspected.address)
        signatureMaps(credentials.signature).any { fields ->
            val publicKey = fields["public_key"]?.let(Scv::fromBytes)
            val signature = fields["signature"]?.let(Scv::fromBytes)
            publicKey != null && signature != null && publicKey.contentEquals(key.publicKey) && key.verify(payload, signature)
        }
    }.getOrDefault(false)

    /** A classic account signs with `vec[map{public_key, signature}]`. */
    private fun signatureMaps(value: SCVal): List<Map<String, SCVal>> {
        val items = if (value.discriminant == SCValType.SCV_VEC) Scv.fromVec(value).toList() else listOf(value)
        return items.filter { it.discriminant == SCValType.SCV_MAP }
            .map { item -> Scv.fromMap(item).entries.associate { (key, v) -> Scv.fromSymbol(key) to v } }
    }
}
