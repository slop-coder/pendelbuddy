package io.github.slop_coder.pendelbuddy.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The API keys: opentransportdata.swiss gives each API its own. */
enum class ApiKey(val file: String) { OJP("ojp-key.bin"), FORMATION("formation-key.bin") }

/**
 * The API keys. They are stored encrypted with an AES key that lives in the Android Keystore and cannot be
 * exported, in noBackupFilesDir (left out of backups and device transfers). They are never logged or shown again.
 */
class Secrets(context: Context) {
    private val dir = context.noBackupFilesDir

    fun has(k: ApiKey) = File(dir, k.file).exists()

    fun hasOjpKey() = has(ApiKey.OJP)

    fun ojpKey() = get(ApiKey.OJP)

    fun setOjpKey(key: String?) = set(ApiKey.OJP, key)

    fun get(k: ApiKey): String? = try {
        val file = File(dir, k.file)
        val bytes = file.takeIf { it.exists() }?.readBytes() ?: return null
        val iv = bytes.copyOfRange(1, 1 + bytes[0])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(bytes, 1 + iv.size, bytes.size - 1 - iv.size), Charsets.UTF_8)
    } catch (e: Exception) {
        // E.g. the Keystore key was lost after a restore: the user has to paste the key again.
        File(dir, k.file).delete()
        null
    }

    fun set(which: ApiKey, key: String?) {
        val file = File(dir, which.file)
        val k = key?.trim()?.removePrefix("Bearer ")?.trim()
        if (k.isNullOrEmpty()) {
            file.delete()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(k.toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pendelbuddy-ojp"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
