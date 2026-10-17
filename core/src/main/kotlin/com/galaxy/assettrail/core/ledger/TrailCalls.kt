package com.galaxy.assettrail.core.ledger

import org.stellar.sdk.Address
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.InvokeContractArgs
import org.stellar.sdk.xdr.SCSymbol
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SorobanAuthorizedFunction
import org.stellar.sdk.xdr.SorobanAuthorizedFunctionType
import org.stellar.sdk.xdr.SorobanAuthorizedInvocation
import org.stellar.sdk.xdr.XdrString

/**
 * The Asset Trail contract calls the driver authorizes, with the argument types the backend uses
 * (backend/src/stellar/trail.ts `TrailContract.args`). The signer service accepts an
 * authorization entry only when it authorizes exactly this invocation, so the types matter: the
 * trip id is an ScVal String, not a Symbol.
 */
object TrailCalls {

    /** T4 / T5 / arrival: `check_in(trip, wp, sha256(payload))`. The contract pays the leg in the same call. */
    fun checkIn(contractId: String, tripId: String, waypoint: Int, payloadHash: ByteArray): SorobanAuthorizedInvocation {
        require(payloadHash.size == 32) { "check_in takes a 32-byte payload hash" }
        return invocation(contractId, "check_in", Scv.toString(tripId), Scv.toUint32(waypoint.toLong()), Scv.toBytes(payloadHash))
    }

    /** T7: `claim_advance(trip)`, for the advance ops granted on the trip. */
    fun claimAdvance(contractId: String, tripId: String): SorobanAuthorizedInvocation =
        invocation(contractId, "claim_advance", Scv.toString(tripId))

    /**
     * After the delivery deadline: `claim_final_pay(trip, sha256(claim payload))`. Only the
     * driver's authorization is needed, so the platform can't hold the final leg back.
     */
    fun claimFinalPay(contractId: String, tripId: String, claimHash: ByteArray): SorobanAuthorizedInvocation {
        require(claimHash.size == 32) { "claim_final_pay takes a 32-byte claim hash" }
        return invocation(contractId, "claim_final_pay", Scv.toString(tripId), Scv.toBytes(claimHash))
    }

    private fun invocation(contractId: String, function: String, vararg args: SCVal): SorobanAuthorizedInvocation =
        SorobanAuthorizedInvocation.builder()
            .function(
                SorobanAuthorizedFunction.builder()
                    .discriminant(SorobanAuthorizedFunctionType.SOROBAN_AUTHORIZED_FUNCTION_TYPE_CONTRACT_FN)
                    .contractFn(
                        InvokeContractArgs.builder()
                            .contractAddress(Address(contractId).toSCAddress())
                            .functionName(SCSymbol(XdrString(function)))
                            .args(arrayOf(*args))
                            .build(),
                    )
                    .build(),
            )
            .subInvocations(arrayOf())
            .build()
}
