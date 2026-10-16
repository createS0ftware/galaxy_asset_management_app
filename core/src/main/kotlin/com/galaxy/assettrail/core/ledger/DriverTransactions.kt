package com.galaxy.assettrail.core.ledger

import com.galaxy.assettrail.core.model.Usdc
import org.stellar.sdk.AbstractTransaction
import org.stellar.sdk.Account
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Memo
import org.stellar.sdk.TimeBounds
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.TransactionPreconditions
import org.stellar.sdk.operations.ClaimClaimableBalanceOperation
import org.stellar.sdk.operations.ManageDataOperation
import org.stellar.sdk.operations.PaymentOperation

/** Signs with the driver's key (D). On a phone the key is wrapped by the Android Keystore. */
interface DriverSigner {
    val accountId: String

    fun sign(transaction: AbstractTransaction)
}

/** A signer holding the seed in memory. For tests and the simulated platform's own accounts. */
class KeyPairSigner(private val keyPair: KeyPair) : DriverSigner {
    override val accountId: String = keyPair.accountId

    override fun sign(transaction: AbstractTransaction) = transaction.sign(keyPair)
}

/**
 * Builds the transactions whose source is the driver's account (spec §4.3). They all share D's
 * sequence number, which is why they go through one ordered local queue (BR-09).
 */
class DriverTransactions(private val config: LedgerConfig) {

    /**
     * T4 pickup, T5 waypoint check-in, or the delivery arrival record: ManageData("wp", h) on the
     * asset account, plus the leg payment from the trip account when [legPay] is set. One atomic
     * transaction, so proof and pay can't drift apart (BR-04). MEMO_HASH carries the same h.
     */
    fun checkIn(
        signer: DriverSigner,
        sequence: Long,
        payloadHash: ByteArray,
        assetAccount: String,
        tripAccount: String,
        legPay: Usdc?,
        maxTimeEpochSeconds: Long,
    ): Transaction {
        require(payloadHash.size == 32) { "MEMO_HASH takes a 32-byte hash" }
        val builder = builder(signer, sequence, maxTimeEpochSeconds)
            .addMemo(Memo.hash(payloadHash))
            .addOperation(
                ManageDataOperation.builder()
                    .name(WAYPOINT_DATA_KEY)
                    .value(payloadHash)
                    .sourceAccount(assetAccount)
                    .build(),
            )
        if (legPay != null && legPay.isPositive) {
            builder.addOperation(
                PaymentOperation.builder()
                    .destination(signer.accountId)
                    .asset(config.usdc)
                    .amount(legPay.toBigDecimal())
                    .sourceAccount(tripAccount)
                    .build(),
            )
        }
        return builder.build().also(signer::sign)
    }

    /** T7: claim a fuel advance that ops approved as a claimable balance. */
    fun claimAdvance(signer: DriverSigner, sequence: Long, balanceId: String, maxTimeEpochSeconds: Long): Transaction =
        builder(signer, sequence, maxTimeEpochSeconds)
            .addOperation(ClaimClaimableBalanceOperation.builder().balanceId(balanceId).build())
            .build()
            .also(signer::sign)

    /** T9: pay the anchor, using the account and memo it returned for this withdrawal (SEP-6). */
    fun cashOut(
        signer: DriverSigner,
        sequence: Long,
        anchorAccount: String,
        amount: Usdc,
        memoHash: ByteArray,
        maxTimeEpochSeconds: Long,
    ): Transaction =
        builder(signer, sequence, maxTimeEpochSeconds)
            .addMemo(Memo.hash(memoHash))
            .addOperation(
                PaymentOperation.builder()
                    .destination(anchorAccount)
                    .asset(config.usdc)
                    .amount(amount.toBigDecimal())
                    .build(),
            )
            .build()
            .also(signer::sign)

    private fun builder(signer: DriverSigner, sequence: Long, maxTimeEpochSeconds: Long): TransactionBuilder {
        // TransactionBuilder uses the account's sequence + 1, so hand it the one before.
        val account = Account(signer.accountId, sequence - 1)
        return TransactionBuilder(account, config.network)
            .setBaseFee(config.baseFee)
            .addPreconditions(
                TransactionPreconditions.builder()
                    .timeBounds(TimeBounds(0, maxTimeEpochSeconds))
                    .build(),
            )
    }

    companion object {
        /** The asset account's single data entry, overwritten at every custody event (§4.5). */
        const val WAYPOINT_DATA_KEY = "wp"
    }
}

object TxReader {

    fun parse(envelopeXdr: String, config: LedgerConfig): Transaction =
        AbstractTransaction.fromEnvelopeXdr(envelopeXdr, config.network) as? Transaction
            ?: throw IllegalArgumentException("Expected a transaction envelope, not a fee bump")

    fun isSignedBy(transaction: AbstractTransaction, accountId: String): Boolean {
        val key = KeyPair.fromAccountId(accountId)
        val hint = key.signatureHint.signatureHint
        val hash = transaction.hash()
        return transaction.signatures.any { decorated ->
            decorated.hint.signatureHint.contentEquals(hint) && key.verify(hash, decorated.signature.signature)
        }
    }
}
