package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.MoodleConnectionMode
import com.example.data.model.MoodleSessionCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.moodleVaultDataStore by preferencesDataStore(name = "moodle_secure_vault")

class MoodleSessionVault(private val context: Context) {

    private companion object {
        const val KEY_ALIAS = "moodle_ai_session_aes_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_SIZE = 12
    }

    private fun keyBaseUrl(uid: String) = stringPreferencesKey("moodle_base_url_$uid")
    private fun keyMode(uid: String) = stringPreferencesKey("moodle_mode_$uid")
    private fun keyEncCookie(uid: String) = stringPreferencesKey("moodle_enc_cookie_$uid")
    private fun keyEncSesskey(uid: String) = stringPreferencesKey("moodle_enc_sesskey_$uid")
    private fun keyEncWsToken(uid: String) = stringPreferencesKey("moodle_enc_wstoken_$uid")
    private fun keyMoodleUserId(uid: String) = stringPreferencesKey("moodle_user_id_$uid")
    private fun keyMoodleDisplayName(uid: String) = stringPreferencesKey("moodle_display_name_$uid")

    fun observeSession(userId: String): Flow<MoodleSessionCredentials> =
        context.moodleVaultDataStore.data.map { prefs ->
            val mode = MoodleConnectionMode.safeFrom(prefs[keyMode(userId)] ?: "")
            MoodleSessionCredentials(
                baseUrl = prefs[keyBaseUrl(userId)] ?: "https://moodle.1skola.lv",
                mode = mode,
                cookieHeader = decrypt(prefs[keyEncCookie(userId)].orEmpty()),
                sesskey = decrypt(prefs[keyEncSesskey(userId)].orEmpty()),
                wsToken = decrypt(prefs[keyEncWsToken(userId)].orEmpty()),
                moodleUserId = prefs[keyMoodleUserId(userId)].orEmpty(),
                moodleDisplayName = prefs[keyMoodleDisplayName(userId)].orEmpty(),
            )
        }

    suspend fun getSession(userId: String): MoodleSessionCredentials =
        observeSession(userId).first()

    suspend fun saveBrowserSession(
        userId: String,
        baseUrl: String,
        cookieHeader: String,
        sesskey: String,
        moodleUserId: String,
        moodleDisplayName: String,
    ) {
        context.moodleVaultDataStore.edit { prefs ->
            prefs[keyBaseUrl(userId)] = baseUrl.trimEnd('/')
            prefs[keyMode(userId)] = MoodleConnectionMode.BROWSER_SESSION.name
            prefs[keyEncCookie(userId)] = encrypt(cookieHeader)
            prefs[keyEncSesskey(userId)] = encrypt(sesskey)
            prefs[keyEncWsToken(userId)] = ""
            prefs[keyMoodleUserId(userId)] = moodleUserId
            prefs[keyMoodleDisplayName(userId)] = moodleDisplayName
        }
    }

    suspend fun saveWebServiceSession(
        userId: String,
        baseUrl: String,
        wsToken: String,
        moodleUserId: String,
        moodleDisplayName: String,
    ) {
        context.moodleVaultDataStore.edit { prefs ->
            prefs[keyBaseUrl(userId)] = baseUrl.trimEnd('/')
            prefs[keyMode(userId)] = MoodleConnectionMode.WEB_SERVICE_API.name
            prefs[keyEncCookie(userId)] = ""
            prefs[keyEncSesskey(userId)] = ""
            prefs[keyEncWsToken(userId)] = encrypt(wsToken)
            prefs[keyMoodleUserId(userId)] = moodleUserId
            prefs[keyMoodleDisplayName(userId)] = moodleDisplayName
        }
    }

    suspend fun clearSession(userId: String) {
        context.moodleVaultDataStore.edit { prefs ->
            prefs.remove(keyMode(userId))
            prefs.remove(keyEncCookie(userId))
            prefs.remove(keyEncSesskey(userId))
            prefs.remove(keyEncWsToken(userId))
            prefs.remove(keyMoodleUserId(userId))
            prefs.remove(keyMoodleDisplayName(userId))
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_SIZE) return ""
            val iv = combined.copyOfRange(0, IV_SIZE)
            val ciphertext = combined.copyOfRange(IV_SIZE, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
