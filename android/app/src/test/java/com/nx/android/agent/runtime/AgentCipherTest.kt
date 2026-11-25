package com.nx.android.agent.runtime

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys are written as blobs: the plain text never reaches the file, and a blob that was
 * changed on the way does not open.
 */
class AgentCipherTest {

    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun aTextGoesThroughAndComesBack() {
        val k = key()
        val blob = AgentCipher.encrypt(k, "sk-secret")

        assertEquals("sk-secret", AgentCipher.decrypt(k, blob))
    }

    @Test
    fun theSameTextGetsADifferentBlobEveryTime() {
        val k = key()

        val first = AgentCipher.encrypt(k, "sk-secret")
        val second = AgentCipher.encrypt(k, "sk-secret")

        assertFalse(first == second)
        assertEquals("sk-secret", AgentCipher.decrypt(k, first))
        assertEquals("sk-secret", AgentCipher.decrypt(k, second))
    }

    @Test
    fun theBlobDoesNotCarryTheText() {
        val blob = AgentCipher.encrypt(key(), "sk-secret")

        assertFalse(blob.contains("sk-secret"))
    }

    @Test
    fun aChangedBlobDoesNotOpen() {
        val k = key()
        val blob = AgentCipher.encrypt(k, "sk-secret")
        val changed = blob.dropLast(2) + (if (blob.endsWith("AA")) "BB" else "AA")

        val opened = runCatching { AgentCipher.decrypt(k, changed) }

        assertTrue(opened.isFailure)
    }
}
