package com.galaxy.assettrail.core.ledger

import com.galaxy.assettrail.core.model.Usdc
import com.galaxy.assettrail.core.payload.Hashes
import org.stellar.sdk.Account
import org.stellar.sdk.Claimant
import org.stellar.sdk.FeeBumpTransaction
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MemoHash
import org.stellar.sdk.Predicate
import org.stellar.sdk.StrKey
import org.stellar.sdk.TimeBounds
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.TransactionPreconditions
import org.stellar.sdk.operations.ClaimClaimableBalanceOperation
import org.stellar.sdk.operations.CreateClaimableBalanceOperation
import org.stellar.sdk.operations.ManageDataOperation
import org.stellar.sdk.operations.PaymentOperation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DriverTransactionsTest {
    private val config = LedgerConfig.Testnet
    private val transactions = DriverTransactions(config)
    private val driver = KeyPair.random()
    private val signer = KeyPairSigner(driver)
    private val assetAccount = KeyPair.random().accountId
    private val tripAccount = KeyPair.random().accountId
    private val hash = Hashes.sha256("payload")
    private val sequence = 263_000_000_000_000_001L

    @Test
    fun `the testnet USDC issuer is a valid address`() {
        assertTrue(StrKey.isValidEd25519PublicKey(LedgerConfig.TESTNET_USDC_ISSUER))
        assertEquals(config.usdc, config.usdc)
    }

    @Test
    fun `T5 records the hash and pays the leg in one transaction`() {
        val tx = transactions.checkIn(signer, sequence, hash, assetAccount, tripAccount, Usdc.of("15.00"), 1_800_000_000)
        val parsed = TxReader.parse(tx.toEnvelopeXdrBase64(), config)

        assertEquals(driver.accountId, parsed.sourceAccount)
        assertEquals(sequence, parsed.sequenceNumber)
        assertContentEquals(hash, assertIs<MemoHash>(parsed.memo).bytes)
        assertEquals(1_800_000_000L, parsed.preconditions.timeBounds.maxTime.toLong())

        val record = assertIs<ManageDataOperation>(parsed.operations[0])
        assertEquals("wp", record.name)
        assertContentEquals(hash, record.value)
        assertEquals(assetAccount, record.sourceAccount)

        val pay = assertIs<PaymentOperation>(parsed.operations[1])
        assertEquals(tripAccount, pay.sourceAccount)
        assertEquals(driver.accountId, pay.destination)
        assertEquals(config.usdc, pay.asset)
        assertEquals(Usdc.of("15.00"), Usdc.of(pay.amount))

        assertTrue(TxReader.isSignedBy(parsed, driver.accountId))
        assertFalse(TxReader.isSignedBy(parsed, assetAccount))
    }

    @Test
    fun `pickup and delivery records carry no payment`() {
        val tx = transactions.checkIn(signer, sequence, hash, assetAccount, tripAccount, null, 1_800_000_000)
        assertEquals(1, tx.operations.size)
    }

    @Test
    fun `fee bump keeps the driver's sequence and signature`() {
        val tx = transactions.checkIn(signer, sequence, hash, assetAccount, tripAccount, Usdc.of("18.00"), 1_800_000_000)
        val feeAccount = KeyPair.random()
        tx.sign(KeyPair.random()) // the platform co-signer
        val bump = FeeBumpTransaction.createWithBaseFee(feeAccount.accountId, 200, tx)
        bump.sign(feeAccount)
        assertEquals(sequence, bump.innerTransaction.sequenceNumber)
        assertTrue(TxReader.isSignedBy(bump.innerTransaction, driver.accountId))
        assertNotEquals(tx.hashHex(), bump.hashHex())
    }

    @Test
    fun `claim uses the balance id the advance transaction produced`() {
        val fee = KeyPair.random()
        val deadline = 1_800_000_000L
        val t6 = TransactionBuilder(Account(fee.accountId, 1L), config.network)
            .setBaseFee(100)
            .addPreconditions(TransactionPreconditions.builder().timeBounds(TimeBounds(0, deadline)).build())
            .addOperation(
                CreateClaimableBalanceOperation.builder()
                    .asset(config.usdc)
                    .amount(Usdc.of("20.00").toBigDecimal())
                    .claimants(
                        listOf(
                            Claimant(driver.accountId, Predicate.AbsBefore(deadline)),
                            Claimant(tripAccount, Predicate.Not(Predicate.AbsBefore(deadline))),
                        ),
                    )
                    .sourceAccount(tripAccount)
                    .build(),
            )
            .build()
        val balanceId = t6.getClaimableBalanceId(0)

        val claim = transactions.claimAdvance(signer, sequence, balanceId, deadline)
        val parsed = TxReader.parse(claim.toEnvelopeXdrBase64(), config)
        assertEquals(balanceId, assertIs<ClaimClaimableBalanceOperation>(parsed.operations.single()).balanceId)
    }

    @Test
    fun `cash-out pays the anchor with its memo`() {
        val anchor = KeyPair.random().accountId
        val memo = Hashes.randomBytes(32)
        val tx = transactions.cashOut(signer, sequence, anchor, Usdc.of("55.00"), memo, 1_800_000_000)
        val pay = assertIs<PaymentOperation>(tx.operations.single())
        assertEquals(anchor, pay.destination)
        assertEquals(null, pay.sourceAccount)
        assertContentEquals(memo, assertIs<MemoHash>(tx.memo).bytes)
    }
}
