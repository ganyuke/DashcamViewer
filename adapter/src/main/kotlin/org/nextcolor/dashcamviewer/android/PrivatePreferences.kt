package org.nextcolor.dashcamviewer.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

private val Context.viewerStore by preferencesDataStore("dashcamviewer-private")

/** Stores sensitive camera credentials only as AES-GCM ciphertext. */
class PrivatePreferences(private val context: Context) {
    private val credentialsKey = stringPreferencesKey("credentials_ciphertext")
    private val historyKey = stringPreferencesKey("owned_download_history")

    suspend fun saveCredentials(ssid: String, password: String) {
        val raw = "$ssid\u0000$password".toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(raw)
            val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            context.viewerStore.edit { it[credentialsKey] = encoded }
        } finally { raw.fill(0) }
    }
    suspend fun loadCredentials(): Pair<String, String>? {
        val encoded = context.viewerStore.data.first()[credentialsKey] ?: return null
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val secret = Cipher.getInstance("AES/GCM/NoPadding")
            secret.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val raw = secret.doFinal(all.copyOfRange(12, all.size))
            val parts = raw.toString(Charsets.UTF_8).split('\u0000', limit = 2)
            raw.fill(0)
            parts.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        } catch (_: Exception) { null }
    }
    suspend fun clearCredentials() { context.viewerStore.edit { it.remove(credentialsKey) } }
    fun journalPendingUri(uri: String?) {
        val prefs = context.getSharedPreferences("pending-media-journal", Context.MODE_PRIVATE)
        if (uri == null) prefs.edit().remove("uris").commit() else {
            val list = prefs.getString("uris", "").orEmpty().split('\n').filter(String::isNotBlank).toMutableSet()
            list += uri
            prefs.edit().putString("uris", list.joinToString("\n")).commit()
        }
    }
    fun clearJournalUri(uri: String) {
        val prefs = context.getSharedPreferences("pending-media-journal", Context.MODE_PRIVATE)
        val list = prefs.getString("uris", "").orEmpty().split('\n').filter { it.isNotBlank() && it != uri }
        if (list.isEmpty()) prefs.edit().remove("uris").commit() else prefs.edit().putString("uris", list.joinToString("\n")).commit()
    }
    fun recoverJournaledRows() {
        val prefs = context.getSharedPreferences("pending-media-journal", Context.MODE_PRIVATE)
        val unresolved = mutableListOf<String>()
        prefs.getString("uris", "").orEmpty().split('\n').filter(String::isNotBlank).forEach { raw ->
            try {
                val uri = android.net.Uri.parse(raw)
                val cursor = context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.IS_PENDING), null, null, null)
                    ?: error("Could not inspect journaled media row")
                val pending = cursor.use {
                    if (!it.moveToFirst()) null else it.getInt(0) == 1
                }
                when (pending) {
                    true -> if (context.contentResolver.delete(uri, null, null) == 0) unresolved += raw
                    false, null -> Unit // Completed or already removed: never delete a completed download.
                }
            } catch (_: Exception) { unresolved += raw }
        }
        if (unresolved.isEmpty()) prefs.edit().remove("uris").commit()
        else prefs.edit().putString("uris", unresolved.joinToString("\n")).commit()
    }
    suspend fun recoverPendingRows() = withContext(Dispatchers.IO) { recoverJournaledRows() }
    suspend fun appendHistory(download: OwnedDownload) {
        val raw = context.viewerStore.data.first()[historyKey].orEmpty()
        val row = listOf(download.uri, download.name, download.mimeType, download.createdAt).joinToString("\t")
        context.viewerStore.edit { it[historyKey] = (raw.lines().filter(String::isNotBlank) + row).distinct().joinToString("\n") }
    }
    suspend fun history(): List<OwnedDownload> = context.viewerStore.data.first()[historyKey].orEmpty().lines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size != 4) null else p[3].toLongOrNull()?.let { OwnedDownload(p[0], p[1], p[2], it) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    private companion object { const val KEY_ALIAS = "dashcamviewer.credentials.v1" }
}
