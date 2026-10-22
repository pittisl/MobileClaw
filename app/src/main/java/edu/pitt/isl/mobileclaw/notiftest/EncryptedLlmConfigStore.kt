package edu.pitt.isl.mobileclaw.notiftest

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class LlmStoredState(
    val profiles: Map<LlmProvider, LlmProfile> = emptyMap(),
    val active: LlmProvider = LlmProvider.CUSTOM,
    val seeded: Boolean = true
)

/**
 * One atomic encrypted document in noBackupFilesDir. Tests supply their own directory
 * and key alias, so they never touch the user's configuration or production key.
 */
internal class EncryptedLlmConfigStore(
    private val directory: File,
    private val keyAlias: String
) : LlmConfigStorage {
    internal val encryptedFile = File(directory, "profiles.enc")
    private val atomic = AtomicFile(encryptedFile)
    private val seedMarker = AtomicFile(File(directory, "seed-consumed"))

    /**
     * Consumes the installation's one developer-seed opportunity BEFORE attempting a
     * read or encryption. This marker has no secret data; it prevents a missing/broken
     * encrypted file from making embedded developer credentials reappear.
     */
    override fun loadOrInitialize(developerSeed: () -> LlmProfile?): LlmStoredState {
        ensureDirectory()
        val hadMarker = exists(seedMarker)
        val hadData = exists(atomic)
        if (!hadMarker) atomicWrite(seedMarker, byteArrayOf(1))
        if (hadData) return read()
        val profile = if (!hadMarker) developerSeed()?.validated() else null
        val state = if (profile == null) LlmStoredState() else
            LlmStoredState(mapOf(profile.provider to profile), profile.provider)
        write(state)
        return state
    }

    override fun write(state: LlmStoredState) {
        ensureDirectory()
        // Also protect calls made while recovering from a failed initial read.
        if (!exists(seedMarker)) atomicWrite(seedMarker, byteArrayOf(1))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(encode(state))
        val bytes = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(FORMAT)
                out.writeInt(cipher.iv.size)
                out.write(cipher.iv)
                out.write(encrypted)
            }
        }.toByteArray()
        atomicWrite(atomic, bytes)
    }

    /** Explicit recovery discards unreadable ciphertext; the seed marker survives. */
    override fun resetUnreadableState() {
        keyStore().deleteEntry(keyAlias)
        atomic.delete()
    }

    private fun read(): LlmStoredState {
        val bytes = atomic.openRead().use {
            require(it.channel.size() in 1..MAX_BYTES.toLong()) { "Invalid encrypted configuration." }
            it.readBytes()
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == FORMAT) { "Unsupported configuration format." }
        val ivSize = input.readInt()
        require(ivSize == 12 && input.available() > ivSize + 16) { "Invalid encrypted configuration." }
        val iv = ByteArray(ivSize).also(input::readFully)
        val ciphertext = ByteArray(input.available()).also(input::readFully)
        val key = keyStore().getKey(keyAlias, null) as? SecretKey
            ?: throw IllegalStateException("Configuration key is unavailable.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return decode(cipher.doFinal(ciphertext))
    }

    private fun encryptionKey(): SecretKey {
        val store = keyStore()
        if (store.containsAlias(keyAlias)) {
            return store.getKey(keyAlias, null) as? SecretKey
                ?: throw IllegalStateException("Configuration key is unavailable.")
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Configuration storage is unavailable." }
    }

    private fun exists(file: AtomicFile): Boolean =
        file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() ||
            File(file.baseFile.path + ".new").exists()

    private fun atomicWrite(file: AtomicFile, bytes: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) file.failWrite(stream)
            throw error
        }
    }

    private fun encode(state: LlmStoredState): ByteArray {
        val profiles = JSONArray()
        state.profiles.toSortedMap(compareBy { it.ordinal }).values.forEach { p ->
            profiles.put(JSONObject()
                .put("provider", p.provider.name).put("baseUrl", p.baseUrl).put("model", p.model)
                .put("apiKey", p.apiKey).put("auth", p.auth.name)
                .put("readTimeoutSeconds", p.readTimeoutSeconds)
                .put("inputBudgetTokens", p.inputBudgetTokens).put("supportsImages", p.supportsImages))
        }
        return JSONObject().put("version", FORMAT).put("active", state.active.name)
            .put("seeded", state.seeded).put("profiles", profiles).toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): LlmStoredState {
        val data = JSONObject(String(bytes, Charsets.UTF_8))
        require(data.getInt("version") == FORMAT && data.getBoolean("seeded"))
        val active = LlmProvider.valueOf(data.getString("active"))
        val array = data.getJSONArray("profiles")
        require(array.length() <= LlmProvider.entries.size)
        val profiles = mutableMapOf<LlmProvider, LlmProfile>()
        for (i in 0 until array.length()) {
            val p = array.getJSONObject(i)
            val profile = LlmProfile(
                LlmProvider.valueOf(p.getString("provider")),
                p.getString("baseUrl"), p.getString("model"), p.getString("apiKey"),
                LlmAuth.valueOf(p.getString("auth")), p.getLong("readTimeoutSeconds"),
                p.getInt("inputBudgetTokens"), p.getBoolean("supportsImages")
            ).validated()
            require(profiles.put(profile.provider, profile) == null)
        }
        return LlmStoredState(profiles.toMap(), active)
    }

    companion object {
        private const val FORMAT = 1
        private const val MAX_BYTES = 256 * 1024
        private val AAD = "MobileClaw LLM profiles v1".toByteArray(Charsets.UTF_8)
    }
}
