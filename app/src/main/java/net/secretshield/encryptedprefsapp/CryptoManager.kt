package net.secretshield.encryptedprefsapp


import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * @param useStrongBox default false for TEE, true = StrongBox. This only matters at the point
 * of key generation. If the key is already generated, this is ignored.
 *
 * [TEE](https://source.android.com/docs/security/features/trusty)
 * [StrongBox](https://developer.android.com/privacy-and-security/keystore#HardwareSecurityModule)
 */
class CryptoManager(private val useStrongBox: Boolean = false) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_TRANSFORM = "AES/GCM/NoPadding"
        // GCM IV size is 12 bytes
        private const val IV_SIZE = 12

    }

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val alias = "encrypted_prefs_key"

    private fun getKey(): SecretKey {
        val existingKey = keyStore.getKey(alias, null) as? SecretKey
        return existingKey ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            PURPOSE_ENCRYPT or PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(useStrongBox)
        }
        val keyGenParameterSpec = builder.build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    fun encryptToString(plaintext: String): String {
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        try {
            return Base64.encodeToString(
                encrypt(plainBytes),
                Base64.NO_WRAP
            )
        } finally {
            plainBytes.fill(0)
        }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ENCRYPTION_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        return iv + ciphertext
    }

    fun decryptToString(encryptedText: String): String {
        val encryptedData = Base64.decode(encryptedText, Base64.NO_WRAP)
        val decryptedBytes = decrypt(encryptedData)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun decrypt(encryptedData: ByteArray): ByteArray {
        val iv = encryptedData.copyOfRange(0, IV_SIZE) // GCM IV size is 12 bytes
        val ciphertext = encryptedData.copyOfRange(IV_SIZE, encryptedData.size)

        val cipher = Cipher.getInstance(ENCRYPTION_TRANSFORM)
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getKey(), gcmSpec)
        return cipher.doFinal(ciphertext)
    }
}
