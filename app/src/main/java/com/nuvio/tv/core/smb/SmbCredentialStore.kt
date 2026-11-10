package com.nuvio.tv.core.smb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbCredentialStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun save(credentialRef: String, password: String) {
        if (credentialRef.isBlank()) return
        if (password.isEmpty()) {
            delete(credentialRef)
            return
        }
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(preferences.edit().putString(credentialRef, value).commit()) {
                "Could not persist SMB credentials"
            }
        }
    }

    fun load(credentialRef: String): String? {
        if (credentialRef.isBlank()) return null
        val value = preferences.getString(credentialRef, null) ?: return null
        return runCatching {
            synchronized(lock) {
                val separator = value.indexOf(':')
                require(separator > 0)
                val iv = Base64.decode(value.substring(0, separator), Base64.NO_WRAP)
                val encrypted = Base64.decode(value.substring(separator + 1), Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            }
        }.getOrNull()
    }

    fun delete(credentialRef: String) {
        if (credentialRef.isBlank()) return
        runCatching { preferences.edit().remove(credentialRef).apply() }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "nuvio_smb_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nuvio_smb_password_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
