package com.igloo.blindpenguincoder.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256/GCM through the AndroidKeyStore. The provider generates a fresh IV per
 * encryption; it is prepended to the ciphertext before Base64 encoding.
 *
 * Every failure returns null rather than throwing: the caller treats that as
 * "no stored secret" and re-authenticates. Nothing is ever logged.
 */
class AndroidKeystoreCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretCipher {

    // Not eager: the container is built on the main thread, and minting a
    // TEE-backed key there would show up as cold-start jank.
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun encrypt(plaintext: String): String? = cryptoOrNull {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        check(iv.size == IV_LENGTH)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    override fun decrypt(blob: String): String? = cryptoOrNull {
        val bytes = Base64.getDecoder().decode(blob)
        if (bytes.size <= IV_LENGTH) return@cryptoOrNull null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH),
        )
        String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val existing = try {
            keyStore.getKey(keyAlias, null) as? SecretKey
        } catch (_: UnrecoverableKeyException) {
            deleteKey()
            null
        }
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Forbids caller-supplied IVs, so IV reuse is impossible by construction.
                .setRandomizedEncryptionRequired(true)
                // A TV has no lock screen and must restore its session on boot
                // with nobody present, so neither user auth nor an unlocked
                // device may gate decryption.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun <T> cryptoOrNull(block: () -> T?): T? = try {
        block()
    } catch (_: KeyPermanentlyInvalidatedException) {
        deleteKey()
        null
    } catch (_: UnrecoverableKeyException) {
        deleteKey()
        null
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: ProviderException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun deleteKey() {
        try {
            keyStore.deleteEntry(keyAlias)
        } catch (_: GeneralSecurityException) {
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_KEY_ALIAS = "igloo_session_key"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
