package com.forma.datasync.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Installation-local secrets and retry payloads; passwords are never written here. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("forma_native_private", Context.MODE_PRIVATE)
    private val pendingFile = File(context.noBackupFilesDir, "forma_pending_batch.enc")
    private val queueDirectory = File(context.noBackupFilesDir, "forma_local_batches")
    private val historyDirectory = File(context.noBackupFilesDir, "forma_collection_history")
    private val localQueue: LocalBatchQueue
        get() = localQueues.getOrPut(queueDirectory.absolutePath) {
            LocalBatchQueue(queueDirectory, ::encrypt, ::decrypt)
        }
    private val historyStore: CollectionHistoryStore
        get() = histories.getOrPut(historyDirectory.absolutePath) {
            CollectionHistoryStore(historyDirectory, ::encrypt, ::decrypt, localQueue::batchIds, localQueue::readBatch)
        }

    fun read(): JSONObject = synchronized(lock) {
        val value = prefs.getString("state", null) ?: return@synchronized JSONObject()
        JSONObject(decrypt(value))
    }

    fun update(change: (JSONObject) -> Unit): JSONObject = synchronized(lock) {
        val state = read()
        change(state)
        check(prefs.edit().putString("state", encrypt(state.toString())).commit()) {
            "Unable to save the encrypted sync state."
        }
        state
    }

    fun readPending(): JSONObject? = synchronized(lock) {
        if (!pendingFile.exists()) null else JSONObject(decrypt(pendingFile.readText()))
    }

    fun writePending(pending: JSONObject) = synchronized(lock) {
        // Rename after fsync keeps the previous retry batch intact if the process is killed.
        val temp = File(pendingFile.parentFile, pendingFile.name + ".tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(encrypt(pending.toString()).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(temp.renameTo(pendingFile)) { "Unable to save the encrypted retry batch." }
    }

    fun clearPending() = synchronized(lock) {
        pendingFile.delete()
        File(pendingFile.parentFile, pendingFile.name + ".tmp").delete()
        Unit
    }

    fun appendLocal(batch: JSONObject) = synchronized(lock) {
        // Raw records commit first. History bootstrap recovers an interrupted index update.
        localQueue.append(batch)
        historyStore.recordBatch(batch)
    }

    fun nextLocal(
        serverUrl: String,
        userId: String,
        deviceId: String,
        excludeBatchIds: Set<String> = emptySet(),
        canUpload: (JSONObject) -> Boolean = { true },
    ): JSONObject? = synchronized(lock) {
        localQueue.next(serverUrl, userId, deviceId, excludeBatchIds, canUpload)?.also(historyStore::recordBatch)
    }

    fun removeLocal(batchId: String) = synchronized(lock) { localQueue.remove(batchId) }

    fun localStats(serverUrl: String? = null, userId: String? = null, deviceId: String? = null): JSONObject = synchronized(lock) {
        localQueue.stats(serverUrl, userId, deviceId)
    }

    fun recoverLocalCursors(collectionEpoch: Long = 0): JSONObject = synchronized(lock) {
        localQueue.recoverCursors(collectionEpoch)
    }

    fun beginCollection(background: Boolean): String = synchronized(lock) { historyStore.begin(background) }

    fun finishCollection(jobId: String, status: String, error: String? = null) = synchronized(lock) {
        historyStore.finish(jobId, status, error)
    }

    fun markLocalSynced(batchId: String, syncedAt: Long) = synchronized(lock) { historyStore.markSynced(batchId, syncedAt) }

    fun markLocalUploadError(batchId: String, message: String) = synchronized(lock) {
        historyStore.markUploadError(batchId, message)
    }

    fun collectionHistory(offset: Int, limit: Int): JSONObject = synchronized(lock) { historyStore.history(offset, limit) }

    fun collectionDetails(jobId: String): JSONObject = synchronized(lock) { historyStore.details(jobId) }

    fun collectionRecords(jobId: String, source: String, offset: Int, limit: Int): JSONObject = synchronized(lock) {
        historyStore.records(jobId, source, offset, limit)
    }

    fun clear() = synchronized(lock) {
        check(prefs.edit().clear().commit()) { "Unable to clear the encrypted sync state." }
        clearPending()
        localQueue.clear()
        historyStore.clear()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "Invalid encrypted sync state." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_ALIAS = "forma-native-v1"
        private val lock = Any()
        // Workers and the bridge share lightweight owner/cursor metadata across polls.
        private val localQueues = mutableMapOf<String, LocalBatchQueue>()
        private val histories = mutableMapOf<String, CollectionHistoryStore>()
    }
}
