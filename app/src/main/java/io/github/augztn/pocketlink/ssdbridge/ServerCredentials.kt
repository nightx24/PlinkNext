package io.github.augztn.pocketlink.ssdbridge

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores only a salted PBKDF2 verifier. The server never needs to recover the
 * configured password after the user enters it.
 */
internal class ServerCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("pocketlink-auth", Context.MODE_PRIVATE).also { current ->
        val legacy = context.getSharedPreferences("homeserver-auth", Context.MODE_PRIVATE)
        if (current.getString("password_salt", null).isNullOrBlank() &&
            current.getString("password_hash", null).isNullOrBlank() &&
            !legacy.getString("password_salt", null).isNullOrBlank() &&
            !legacy.getString("password_hash", null).isNullOrBlank()) {
            check(current.edit()
                .putString("password_salt", legacy.getString("password_salt", null))
                .putString("password_hash", legacy.getString("password_hash", null))
                .commit()) { "Could not migrate the saved server password verifier." }
        }
    }

    fun isConfigured(): Boolean =
        !prefs.getString("password_salt", null).isNullOrBlank() &&
            !prefs.getString("password_hash", null).isNullOrBlank()

    @Synchronized
    fun setPassword(password: String) {
        require(password.length >= 12) { "Use a password with at least 12 characters." }
        require(password.length <= 256) { "Password is too long." }

        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt)
        check(prefs.edit()
            .putString("password_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("password_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .commit()) { "Could not save the server password verifier." }
    }

    fun verify(password: CharArray): Boolean {
        return try {
            val saltText = prefs.getString("password_salt", null) ?: return false
            val hashText = prefs.getString("password_hash", null) ?: return false
            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val expected = Base64.decode(hashText, Base64.NO_WRAP)
            MessageDigest.isEqual(expected, derive(String(password), salt))
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            password.fill('\u0000')
        }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val SALT_BYTES = 16
        const val ITERATIONS = 210_000
        const val HASH_BITS = 256
    }
}
