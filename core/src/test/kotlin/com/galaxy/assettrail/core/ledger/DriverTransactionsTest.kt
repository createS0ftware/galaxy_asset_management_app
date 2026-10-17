package com.galaxy.assettrail.core.ledger

import com.galaxy.assettrail.core.model.Usdc
import org.stellar.sdk.Account
import org.stellar.sdk.ChangeTrustAsset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Memo
import org.stellar.sdk.Network
import org.stellar.sdk.StrKey
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.operations.BeginSponsoringFutureReservesOperation
import org.stellar.sdk.operations.ChangeTrustOperation
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.EndSponsoringFutureReservesOperation
import org.stellar.sdk.operations.Operation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.operations.SetOptionsOperation
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriverTransactionsTest {
    private val config = LedgerConfig.Testnet
    private val network: Network = config.network
    private val driver = KeyPair.random()
    private val fee = KeyPair.random()
    private val anchor = KeyPair.random().accountId

    private fun tx(source: String, memo: String? = null, vararg ops: Operation): Transaction =
        TransactionBuilder(Account(source, 41L), network)
            .setBaseFee(100)
            .setTimeout(60)
            .apply { memo?.let { addMemo(Memo.text(it)) } }
            .apply { ops.forEach { addOperation(it) } }
            .build()

    /** T2 exactly as the backend builds it (backend/src/stellar/accounts.ts sponsoredAccountOps). */
    private fun walletOps(trustAsset: org.stellar.sdk.Asset = config.usdc) = arrayOf<Operation>(
        BeginSponsoringFutureReservesOperation.builder().sponsoredId(driver.accountId).sourceAccount(fee.accountId).build(),
        CreateAccountOperation.builder().destination(driver.accountId).startingBalance(BigDecimal.ZERO).sourceAccount(fee.accountId).build(),
        ChangeTrustOperation.builder().asset(ChangeTrustAsset(trustAsset)).limit(BigDecimal("922337203685.4775807")).sourceAccount(driver.accountId).build(),
        EndSponsoringFutureReservesOperation.builder().sourceAccount(driver.accountId).build(),
    )

    @Test
    fun `the testnet USDC issuer is a valid address`() {
        assertTrue(StrKey.isValidEd25519PublicKey(LedgerConfig.TESTNET_USDC_ISSUER))
    }

    @Test
    fun `the platform's wallet set-up passes and is signed by the driver only`() {
        val prepared = tx(fee.accountId, null, *walletOps())
        assertNull(DriverTransactions.walletProblem(prepared, driver.accountId, config.usdc))
        val signed = DriverTransactions.parse(DriverTransactions.sign(prepared, KeyPairSigner(driver)), network)
        assertTrue(DriverTransactions.isSignedBy(signed, driver.accountId))
        assertFalse(DriverTransactions.isSignedBy(signed, fee.accountId))
        assertEquals(prepared.hashHex(), signed.hashHex())
    }

    @Test
    fun `a wallet set-up that does more than the trustline is refused`() {
        val extraSigner = SetOptionsOperation.builder().sourceAccount(driver.accountId).build()
        assertNotNull(DriverTransactions.walletProblem(tx(fee.accountId, null, *walletOps(), extraSigner), driver.accountId, config.usdc))
        assertNotNull(DriverTransactions.walletProblem(tx(driver.accountId, null, *walletOps()), driver.accountId, config.usdc))
        val otherAsset = org.stellar.sdk.Asset.createNonNativeAsset("USDC", KeyPair.random().accountId)
        assertNotNull(DriverTransactions.walletProblem(tx(fee.accountId, null, *walletOps(otherAsset)), driver.accountId, config.usdc))
    }

    @Test
    fun `cash-out must be one payment of the quoted amount to the anchor`() {
        fun payment(amount: String, to: String = anchor) =
            PaymentOperation.builder().destination(to).asset(config.usdc).amount(BigDecimal(amount)).build()
        val good = tx(driver.accountId, "WD-7K2Q", payment("55.00"))
        assertNull(DriverTransactions.cashOutProblem(good, driver.accountId, config.usdc, Usdc.of("55.00"), anchor, "WD-7K2Q"))
        assertNotNull(DriverTransactions.cashOutProblem(good, driver.accountId, config.usdc, Usdc.of("50.00"), anchor, "WD-7K2Q"))
        assertNotNull(DriverTransactions.cashOutProblem(good, driver.accountId, config.usdc, Usdc.of("55.00"), anchor, "OTHER"))
        val elsewhere = tx(driver.accountId, "WD-7K2Q", payment("55.00", KeyPair.random().accountId))
        assertNotNull(DriverTransactions.cashOutProblem(elsewhere, driver.accountId, config.usdc, Usdc.of("55.00"), anchor, "WD-7K2Q"))
        val two = tx(driver.accountId, "WD-7K2Q", payment("55.00"), payment("1.00", KeyPair.random().accountId))
        assertNotNull(DriverTransactions.cashOutProblem(two, driver.accountId, config.usdc, Usdc.of("55.00"), anchor, "WD-7K2Q"))
    }
}
