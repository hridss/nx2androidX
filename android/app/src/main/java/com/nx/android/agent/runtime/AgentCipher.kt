package com.nx.android.agent.runtime

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The keys' cipher: AES in GCM mode. The blob is the iv and the ciphertext, base64'd.
 *
 * The iv is made by whoever holds the key: the phone's keystore refuses an iv the caller brings
 * along ("Caller-provided IV not permitted"), so the cipher asks for one after it is set up.
 *
 * Free of Android, so the JVM tests drive it.
 */
object AgentCipher {

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** The text as a blob: base64 of the iv and the ciphertext. */
    fun encrypt(key: SecretKey, text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val body = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + body)
    }

    /** The blob back to the text. */
    fun decrypt(key: SecretKey, blob: String): String {
        val raw = Base64.getDecoder().decode(blob)
        require(raw.size > IV_BYTES) { "the blob is too short to hold an iv" }
        val iv = raw.copyOfRange(0, IV_BYTES)
        val body = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }
}
