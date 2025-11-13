@file:Suppress("unused")
package net.secretshield.encryptedprefsapp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Base64

/**
 * This implementation uses the Android keystore to encrypt/decrypt a key that Tink would use
 * to encrypt/decrypt data.
 *
 * Reference: https://blog.kinto-technologies.com/posts/2025-06-16-encrypted-shared-preferences-migration-en/
 */
class TinkCryptoManager(
    context: Context,
    private val dataStore: DataStore<Preferences>,
    private val keysetKey: Preferences.Key<String> = stringPreferencesKey("encrypted_keyset")
) {
    val keysetName = "keyset_name"
    val prefFileName = "pref_file_name"
    val packageName: String = context.packageName

    var aead: Aead

    init {
        AeadConfig.register()
        aead = buildAead(context)
    }

    private fun buildAead(context: Context): Aead {
//        return AndroidKeysetManager.Builder()
//            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
//            .withMasterKeyUri("android-keystore://tink_master_key")
//            // the following would cause the key to be stored in SharedPreferences
//            // but it is questionable if SharedPreferences is long terms, we prefer DataStore
//            .withSharedPref(
//                context,
//                "$packageName.$keysetName",
//                "$packageName.$prefFileName"
//            )
//            .build()
//            .keysetHandle
//            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

        val masterKey = AndroidKeystoreKmsClient()
            .getAead("android-keystore://tink_master_key")

        val keysetHandle = try {
            // Try to read existing keyset
            readKeyset(masterKey)
        } catch (e: Exception) {
            // Generate new keyset if not found
            val newHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            writeKeyset(newHandle, masterKey)
            newHandle
        }

        return keysetHandle
            .getPrimitive(
                RegistryConfiguration.get(),
                Aead::class.java
            )
    }


    private fun readKeyset(masterKey: Aead): KeysetHandle = runBlocking {
        val keysetString = dataStore.data.first()[keysetKey]
            ?: throw IOException("Keyset not found")

        val keysetBytes = Base64.getDecoder().decode(keysetString)

        // Modern API: Use TinkProtoKeysetFormat
        TinkProtoKeysetFormat.parseEncryptedKeyset(
            keysetBytes,
            masterKey,
            /* associatedData= */ ByteArray(0)
        )
    }

    private fun writeKeyset(keysetHandle: KeysetHandle, masterKey: Aead) = runBlocking {
        val encryptedKeyset = TinkProtoKeysetFormat.serializeEncryptedKeyset(
            keysetHandle,
            masterKey,
            /* associatedData= */ ByteArray(0)
        )

        val keysetString = Base64.getEncoder().encodeToString(encryptedKeyset)
        dataStore.edit { preferences ->
            preferences[keysetKey] = keysetString
        }
    }


    fun encryptToString(inputByteArray: ByteArray): Result<String> {
        return runCatching {
            val encrypted = encrypt(inputByteArray = inputByteArray).getOrThrow()
            Base64.getEncoder().encodeToString(encrypted)
        }
    }

    fun encrypt(inputByteArray: ByteArray): Result<ByteArray> {
        return runCatching {
            aead.encrypt(inputByteArray, null)
        }
    }

    fun decryptToString(inputEncryptedString: String): Result<ByteArray> {
        return runCatching {
            val encrypted = Base64.getDecoder().decode(inputEncryptedString)
            decrypt(inputEncryptedByteArray = encrypted).getOrThrow()
        }
    }

    fun decrypt(inputEncryptedByteArray: ByteArray): Result<ByteArray> {
        return runCatching {
            aead.decrypt(inputEncryptedByteArray, null)
        }
    }
}
