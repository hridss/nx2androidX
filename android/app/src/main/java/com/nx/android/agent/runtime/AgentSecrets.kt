package com.nx.android.agent.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The keys' keeper: a key in the phone's own keystore that cannot be read out of it, and the
 * cipher that uses it. The key never leaves the phone — a backup of the app's files carries the
 * ciphertext and nothing to open it with.
 */
@Singleton
class AgentSecrets @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    private companion object {
        const val ALIAS = "nx2a-agent-keys"
        const val PROVIDER = "AndroidKeyStore"
    }

    /** The keystore's key: made once, used for every key the player saves. */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    /** The text as a blob. */
    fun encrypt(text: String): String = AgentCipher.encrypt(key(), text)

    /** The blob back to the text (empty when it cannot be opened). */
    fun decrypt(blob: String): String = runCatching { AgentCipher.decrypt(key(), blob) }.getOrDefault("")
}
