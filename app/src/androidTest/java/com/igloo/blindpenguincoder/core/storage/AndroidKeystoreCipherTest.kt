package com.igloo.blindpenguincoder.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The only coverage that exercises a real AndroidKeyStore — JVM unit tests cannot.
 * Uses a dedicated alias so the app's own session key is never touched.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCipherTest {

    private val alias = "igloo_test_session_key"
    private val cipher = AndroidKeystoreCipher(keyAlias = alias)

    @After
    fun deleteTestKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun roundTripReturnsTheOriginalPlaintext() {
        val plaintext = """{"name":"session","value":"abc123","hôte":"igloo.tëst"}"""

        val blob = cipher.encrypt(plaintext)

        assertNotNull(blob)
        assertEquals(plaintext, cipher.decrypt(blob!!))
    }

    /** The real guard on setRandomizedEncryptionRequired: a fresh IV every time. */
    @Test
    fun theSamePlaintextEncryptsToDifferentBlobs() {
        val plaintext = "abc123"

        val first = cipher.encrypt(plaintext)!!
        val second = cipher.encrypt(plaintext)!!

        assertNotEquals(first, second)
        assertEquals(plaintext, cipher.decrypt(first))
        assertEquals(plaintext, cipher.decrypt(second))
    }

    @Test
    fun aTamperedBlobDecryptsToNull() {
        val bytes = Base64.getDecoder().decode(cipher.encrypt("abc123")!!)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()

        assertNull(cipher.decrypt(Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun aLegacyPlaintextValueDecryptsToNull() {
        assertNull(cipher.decrypt("""{"name":"session","value":"abc123"}"""))
    }

    @Test
    fun aTruncatedBlobDecryptsToNull() {
        assertNull(cipher.decrypt(Base64.getEncoder().encodeToString(ByteArray(4))))
    }
}
