package com.igloo.blindpenguincoder.core.storage

/**
 * Reversible stand-in; AndroidKeyStore is unavailable on the JVM. Reversal rather
 * than identity, so a test can tell a stored blob from the plaintext it came from.
 */
class FakeSecretCipher(
    var failEncrypt: Boolean = false,
    var failDecrypt: Boolean = false,
) : SecretCipher {
    override fun encrypt(plaintext: String): String? =
        if (failEncrypt) null else plaintext.reversed()

    override fun decrypt(blob: String): String? =
        if (failDecrypt) null else blob.reversed()
}
