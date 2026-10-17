package com.galaxy.assettrail.core.ledger

import com.galaxy.assettrail.core.model.Usdc
import org.stellar.sdk.AbstractTransaction
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MemoText
import org.stellar.sdk.Network
import org.stellar.sdk.Transaction
import org.stellar.sdk.operations.BeginSponsoringFutureReservesOperation
import org.stellar.sdk.operations.ChangeTrustOperation
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.EndSponsoringFutureReservesOperation
import org.stellar.sdk.operations.Operation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.xdr.DecoratedSignature
import org.stellar.sdk.xdr.Signature

/** Signs with the driver's key (D). On a phone the key is wrapped by the Android Keystore. */
interface DriverSigner {
    val accountId: String

    /** ed25519 signature over [data]: a transaction hash or a Soroban authorization payload hash. */
    fun sign(data: ByteArray): ByteArray
}

/** A signer holding the seed in memory. For tests and the simulated platform's own accounts. */
class KeyPairSigner(private val keyPair: KeyPair) : DriverSigner {
    override val accountId: String = keyPair.accountId

    override fun sign(data: ByteArray): ByteArray = keyPair.sign(data)
}

/**
 * The classic transactions the driver signs. The platform builds them (it knows the fee account's
 * and the anchor's details); the phone checks each one before its key touches it, because a
 * signature can't be taken back. Check-ins and claims aren't here: they are Soroban
 * authorization entries ([AuthEntries]).
 */
object DriverTransactions {

    fun parse(envelopeXdr: String, network: Network): Transaction =
        AbstractTransaction.fromEnvelopeXdr(envelopeXdr, network) as? Transaction
            ?: throw IllegalArgumentException("Expected a transaction envelope, not a fee bump")

    /** Adds the driver's signature and returns the signed envelope. */
    fun sign(transaction: Transaction, signer: DriverSigner): String {
        val hint = KeyPair.fromAccountId(signer.accountId).signatureHint
        val signature = Signature(signer.sign(transaction.hash()))
        transaction.addSignature(DecoratedSignature.builder().hint(hint).signature(signature).build())
        return transaction.toEnvelopeXdrBase64()
    }

    fun isSignedBy(transaction: AbstractTransaction, accountId: String): Boolean {
        val key = KeyPair.fromAccountId(accountId)
        val hint = key.signatureHint.signatureHint
        val hash = transaction.hash()
        return transaction.signatures.any { decorated ->
            decorated.hint.signatureHint.contentEquals(hint) && key.verify(hash, decorated.signature.signature)
        }
    }

    /**
     * T2 as the backend builds it (backend/src/stellar/accounts.ts): the fee account sponsors and
     * creates the driver's account, the driver's account adds the USDC trustline and ends the
     * sponsorship. The driver's signature covers only those last two operations, and the fee
     * account pays. Returns why the phone must not sign it, or null.
     */
    fun walletProblem(transaction: Transaction, driverAccount: String, payAsset: Asset): String? {
        if (transaction.sourceAccount == driverAccount) return "the set-up would be paid from your account"
        val ops = transaction.operations
        if (ops.size != 4) return "it has ${ops.size} operations instead of 4"
        val begin = ops[0] as? BeginSponsoringFutureReservesOperation ?: return "operation 1 isn't the start of a sponsorship"
        val create = ops[1] as? CreateAccountOperation ?: return "operation 2 doesn't create your account"
        val trust = ops[2] as? ChangeTrustOperation ?: return "operation 3 isn't a trustline"
        val end = ops[3] as? EndSponsoringFutureReservesOperation ?: return "operation 4 doesn't end the sponsorship"
        return when {
            begin.sponsoredId != driverAccount || begin.sourceOf(transaction) == driverAccount -> "the sponsorship isn't for your account"
            create.destination != driverAccount || create.sourceOf(transaction) == driverAccount -> "it doesn't create your account"
            create.startingBalance.signum() != 0 -> "it moves XLM into your account"
            trust.sourceOf(transaction) != driverAccount || trust.asset.asset != payAsset -> "the trustline isn't for ${payAsset.display()}"
            end.sourceOf(transaction) != driverAccount -> "the sponsorship isn't ended by your account"
            else -> null
        }
    }

    /**
     * T9 as the backend builds it (backend/src/services/cashout.ts): one payment of the quoted USDC
     * from the driver's account to the anchor, with the anchor's text memo. [payTo] and [memo] come
     * from the quote; when the platform didn't give them, only the amount and asset are checked.
     * Returns why the phone must not sign it, or null.
     */
    fun cashOutProblem(
        transaction: Transaction,
        driverAccount: String,
        payAsset: Asset,
        amount: Usdc,
        payTo: String?,
        memo: String?,
    ): String? {
        if (transaction.sourceAccount != driverAccount) return "it isn't from your wallet"
        val payment = transaction.operations.singleOrNull() as? PaymentOperation ?: return "it isn't a single payment"
        return when {
            payment.sourceOf(transaction) != driverAccount -> "the payment isn't from your wallet"
            payment.asset != payAsset -> "it doesn't pay ${payAsset.display()}"
            Usdc.of(payment.amount) != amount -> "it pays ${Usdc.of(payment.amount).display()} instead of ${amount.display()}"
            payTo != null && payment.destination != payTo -> "it pays someone other than the anchor"
            memo != null && (transaction.memo as? MemoText)?.text != memo -> "its memo isn't the anchor's"
            else -> null
        }
    }

    private fun Operation.sourceOf(transaction: Transaction): String = sourceAccount ?: transaction.sourceAccount

    private fun Asset.display(): String = toString().substringBefore(':')
}
