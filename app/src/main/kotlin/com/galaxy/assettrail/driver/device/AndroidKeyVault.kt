package com.galaxy.assettrail.driver.device

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.galaxy.assettrail.core.driver.DriverKeyStore
import com.galaxy.assettrail.core.ledger.DriverSigner
import com.galaxy.assettrail.core.payload.Hashes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.stellar.sdk.AbstractTransaction
import org.stellar.sdk.KeyPair
import org.stellar.sdk.StrKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * The driver's Stellar key (ed25519). The seed is encrypted with an AES-256-GCM key that lives in
 * the Android Keystore (StrongBox when the phone has it), so it can't be read off the phone, and
 * it's decrypted only for the moment of signing. The file sits in no-backup storage.
 */
class AndroidKeyVault(context: Context) : DriverKeyStore {

    @Serializable
    private data class Sealed(val accountId: String, val iv: String, val ciphertext: String)

    private val file = File(context.noBackupFilesDir, "wallet-key.json")
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun accountId(): String? = read()?.accountId

    override fun create(): String {
        val keyPair = KeyPair.random()
        val seed = StrKey.decodeEd25519SecretSeed(keyPair.secretSeed)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val sealed = Sealed(keyPair.accountId, Hashes.base64(cipher.iv), Hashes.base64(cipher.doFinal(seed)))
            file.writeText(Json.encodeToString(Sealed.serializer(), sealed))
        } finally {
            seed.fill(0)
        }
        return keyPair.accountId
    }

    override fun signer(): DriverSigner {
        val sealed = read() ?: error("No wallet key on this phone")
        return object : DriverSigner {
            override val accountId: String = sealed.accountId

            override fun sign(transaction: AbstractTransaction) {
                val seed = decrypt(sealed)
                try {
                    transaction.sign(KeyPair.fromSecretSeed(seed))
                } finally {
                    seed.fill(0)
                }
            }
        }
    }

    override fun delete() {
        file.delete()
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    /** Where the wrapping key lives, for the Account screen. */
    fun protection(): Protection {
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: return Protection.NONE
        val info = runCatching {
            SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        }.getOrNull() ?: return Protection.UNKNOWN
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> Protection.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Protection.TRUSTED_ENVIRONMENT
                else -> Protection.SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) Protection.TRUSTED_ENVIRONMENT else Protection.SOFTWARE
        }
    }

    enum class Protection { STRONGBOX, TRUSTED_ENVIRONMENT, SOFTWARE, UNKNOWN, NONE }

    private fun read(): Sealed? =
        if (file.exists()) runCatching { Json.decodeFromString(Sealed.serializer(), file.readText()) }.getOrNull() else null

    private fun decrypt(sealed: Sealed): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, Hashes.fromBase64(sealed.iv)))
        return cipher.doFinal(Hashes.fromBase64(sealed.ciphertext))
    }

    private fun wrappingKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "driver-wallet-wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
