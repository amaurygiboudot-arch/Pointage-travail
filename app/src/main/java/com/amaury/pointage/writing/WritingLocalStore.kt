package com.amaury.pointage.writing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private account-scoped encrypted files excluded from cloud/device backup. Caller performs IO off main. */
internal class WritingLocalStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "writing-v1").apply { mkdirs() }
    private fun file(scope: String, name: String) = AtomicFile(File(directory,
        (if (name.startsWith("draft:")) "draft-" else "dictionary-") + MessageDigest.getInstance("SHA-256").digest("$scope\u0000$name".toByteArray()).joinToString("") { "%02x".format(it) }))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(scope: String, name: String): JSONObject? {
        val source = file(scope, name)
        if (!source.baseFile.exists()) return null
        val bytes = source.readFully()
        require(bytes.size in 29..1_000_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("$scope\u0000$name".toByteArray())
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    private fun write(scope: String, name: String, json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("$scope\u0000$name".toByteArray())
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val target = file(scope, name)
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
    }
    fun purgeExpiredDrafts() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.name.startsWith("draft-") && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
    fun saveDraft(scope: String, document: String, snapshot: WritingEngine.Snapshot) {
        require(snapshot.text.length <= 50_000)
        if (snapshot.text.isEmpty()) { clearDraft(scope, document); return }
        write(scope, "draft:$document", JSONObject().put("text", snapshot.text).put("start", snapshot.start)
            .put("end", snapshot.end).put("saved", System.currentTimeMillis()))
    }
    fun loadDraft(scope: String, document: String): WritingEngine.Snapshot? {
        val value = read(scope, "draft:$document") ?: return null
        if (System.currentTimeMillis() - value.optLong("saved") > 7 * 24 * 60 * 60 * 1000L) {
            clearDraft(scope, document); return null
        }
        val text = value.getString("text")
        return WritingEngine.Snapshot(text, value.optInt("start").coerceIn(0, text.length), value.optInt("end").coerceIn(0, text.length))
    }
    fun clearDraft(scope: String, document: String) = file(scope, "draft:$document").delete()
    fun dictionary(scope: String, language: String): Set<String> = read(scope, "dictionary:$language")?.optJSONArray("words")?.let { words ->
        (0 until words.length()).map { words.getString(it) }.toSet()
    } ?: emptySet()
    fun saveDictionary(scope: String, language: String, words: Set<String>) {
        require(words.size <= 1000 && words.all { it.length in 1..80 && it.none(Char::isISOControl) })
        write(scope, "dictionary:$language", JSONObject().put("words", org.json.JSONArray(words.sorted())))
    }
    companion object { private const val ALIAS = "horatrack.writing.v1" }
}
