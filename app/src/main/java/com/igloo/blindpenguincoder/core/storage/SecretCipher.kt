package com.igloo.blindpenguincoder.core.storage

/** Encrypts secrets at rest. Implementations must never log plaintext or ciphertext. */
interface SecretCipher {
    /** Returns an opaque blob, or null if this device cannot encrypt. */
    fun encrypt(plaintext: String): String?

    /** Returns the plaintext, or null if [blob] cannot be decrypted for any reason. */
    fun decrypt(blob: String): String?
}
