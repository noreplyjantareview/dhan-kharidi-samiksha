package com.vsk.attendance.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SessionStorageException : IllegalStateException(
    "Secure device storage could not be opened. Sign in again; admin activation may be required."
)

/** Biometric templates and the installation credential never enter plaintext preferences. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("vsk_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredSession(val staff: Staff, val kendras: List<Kendra>)

    init {
        // A legacy mobile-only session is not proof of device activation.
        if (prefs.contains("staff") || prefs.contains("kendras")) {
            prefs.edit().remove("staff").remove("kendras").remove(SESSION).commit()
        }
    }

    @Synchronized
    fun save(staff: Staff, kendras: List<Kendra>) {
        try {
            // Do not save an authorized session without its original installation credential.
            val token = prefs.getString(TOKEN, null) ?: throw SessionStorageException()
            require(TOKEN_PATTERN.matches(decrypt(TOKEN, token)))
            val encrypted = encrypt(SESSION, json.encodeToString(StoredSession(staff, kendras)))
            check(prefs.edit().putString(SESSION, encrypted).commit())
        } catch (_: Exception) {
            invalidate()
            throw SessionStorageException()
        }
    }

    @Synchronized
    fun loadSession(): Pair<Staff, List<Kendra>>? {
        return try {
            val encrypted = prefs.getString(SESSION, null) ?: return null
            val token = prefs.getString(TOKEN, null) ?: throw SessionStorageException()
            require(TOKEN_PATTERN.matches(decrypt(TOKEN, token)))
            // Decode both together: failure of either must never leave a signed-in staff object.
            val saved = json.decodeFromString<StoredSession>(decrypt(SESSION, encrypted))
            saved.staff to saved.kendras
        } catch (_: Exception) {
            invalidate()
            null
        }
    }

    @Synchronized
    fun deviceToken(): String {
        try {
            prefs.getString(TOKEN, null)?.let {
                return decrypt(TOKEN, it).also { token -> require(TOKEN_PATTERN.matches(token)) }
            }
            // A lost credential cannot be replaced underneath an existing session.
            check(!prefs.contains(SESSION))
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val alphabet = "0123456789abcdef"
            val token = buildString(64) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(alphabet[value ushr 4])
                    append(alphabet[value and 15])
                }
            }
            bytes.fill(0)
            check(prefs.edit().putString(TOKEN, encrypt(TOKEN, token)).commit())
            return token
        } catch (_: Exception) {
            invalidate()
            throw SessionStorageException()
        }
    }

    /** Normal sign-out keeps device activation; only the employee session is removed. */
    @Synchronized
    fun clear() {
        prefs.edit().remove(SESSION).remove("staff").remove("kendras").commit()
    }

    private fun invalidate() {
        prefs.edit().remove(SESSION).remove(TOKEN).remove("staff").remove("kendras").commit()
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    private fun encrypt(name: String, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true)) // Keystore generates a fresh IV per write.
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(name: String, encrypted: String): String {
        val parts = encrypted.split(':')
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private companion object {
        const val SESSION = "encrypted_session_v3"
        const val TOKEN = "encrypted_device_token_v3"
        const val KEY_ALIAS = "vsk_session_aes_gcm_v3"
        val TOKEN_PATTERN = Regex("[0-9a-f]{64}")
    }
}
