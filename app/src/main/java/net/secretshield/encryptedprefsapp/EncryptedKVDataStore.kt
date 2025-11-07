package net.secretshield.encryptedprefsapp

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import net.secretshield.encryptedprefsapp.proto.EncryptedDataProto
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * As per the [official documentation](https://developer.android.com/topic/libraries/architecture/datastore#proto-create),
 * A custom serializer is required for use with our protobuf. This will be passed to the datastore.
 */
private object EncryptedDataSerializer : Serializer<EncryptedDataProto.EncryptedData> {
    override val defaultValue: EncryptedDataProto.EncryptedData = EncryptedDataProto.EncryptedData.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): EncryptedDataProto.EncryptedData {
        try {
            return EncryptedDataProto.EncryptedData.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            // the next line is critical to cause the corruptionHandler to be invoked
            throw CorruptionException("Cannot read proto.", e)
        }
    }

    override suspend fun writeTo(t: EncryptedDataProto.EncryptedData, output: OutputStream) {
        t.writeTo(output)
    }
}

/**
 * EncryptedKVDataStore is a factory initializer for DataStore using custom EncryptedData
 * for storing encrypted key-value pairs as ByteArray (no base64 except for use by the backup file).
 *
 * On Android, `DataStores` (`androidx.datastore`) must be singletons per file and there is no
 * concept of closing it and opening it again. It must be the same exact instance per
 * DataStore file location.
 *
 * This class maintains the singleton reference keyed to the `storageLocation(dataStoreName).path`.
 *
 * @param context is the android context used for the default initialization storageLocation
 * and backupLocation. If you provide your own, implementations for those parameters, then context
 * may be null.
 * @param dataStoreName is the name of the DataStore file.
 * @param storageLocation is the location of the DataStore file as a function receiving the
 * `dataStoreName` and returning a File object to that location.
 * @param backupDelayDuration is the delay before creating a backup upon insert, update, delete
 * operations. If there are multiple operations before the duration is reached, then all those
 * operations will be included in a single backup
 * @param backupLocation is the location of the backup file as a function receiving the
 * `dataStoreName` plus `BACKUP_FILE_EXT` and returning a File object to that location.
 * @param backupScope is coroutine scope to run the creation of the backup file, if null or
 * cancelled then no backup will be created.
 *
 * @throws Exception if context is null and no initialization values are provided for
 * `storageLocation` or `backupLocation`
 */
class EncryptedKVDataStore(
    private val context: Context?,
    dataStoreName: String = DEFAULT_DATASTORE_NAME,
    storageLocation: ((String) -> File) = { fileName ->
        context ?: throw Exception("Context is required for default initialization")
        File(context.filesDir, fileName)
    },
    backupDelayDuration: Duration = DEFAULT_BACKUP_DELAY_DURATION,
    backupLocation: ((String) -> File) = { fileName ->
        context ?: throw Exception("Context is required for default initialization")
        File(context.filesDir, fileName)
    },
    backupScope: CoroutineScope? = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : IEncryptedDataStore {
    internal data class EncDsConfig(
        val dataStore: DataStore<EncryptedDataProto.EncryptedData>,
        val dataStoreName: String,
        val storageLocation: (String) -> File,
        val backupDelayDuration: Duration,
        val backupLocation: (String) -> File,
        val inDelayPeriod: AtomicBoolean
    )

    companion object {
        private const val DEFAULT_DATASTORE_NAME = "encrypted_data"
        internal const val BACKUP_FILE_EXT = ".backup.json"
        private val DEFAULT_BACKUP_DELAY_DURATION = 10.seconds

        internal var singletonEncDataStoreMap: ConcurrentMap<String, EncDsConfig> =
            ConcurrentHashMap()
    }

    val dataStore: DataStore<EncryptedDataProto.EncryptedData>
    val cryptoManager = CryptoManager()
    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val pDataStoreName: String
    val pBackupDelayDuration: Duration
    val pBackupLocation: (String) -> File
    val pBackupScope: CoroutineScope?

    private val inDelayPeriod: AtomicBoolean

    init {
        val corruptionHandler = ReplaceFileCorruptionHandler(
            produceNewData = { _ ->
                // Note on learning, this code may not be called on "open" or creating a new
                // instance, instead this code may be invoked when accessing
                // the `datastore` or `datastore.data`

                // Try to restore from backup
                if (backupFile.exists()) {
                    try {
                        val backupJson = backupFile.readText()
                        val backupData: Map<String, String> = Json.decodeFromString(backupJson)
                        var newKVStore = EncryptedDataProto.EncryptedData
                            .newBuilder()
                            .clear()
                            .putAllKeyValues(
                                backupData.mapValues {
                                    ByteString.copyFrom(
                                        Base64.decode(it.value, Base64.DEFAULT)
                                    )
                                }
                            )
                            .build()
                        newKVStore
                    } catch (e: Exception) {
                        Log.e("EncryptedKV", "Corruption: Error restoring from backup", e)
                        // Backup is corrupt or another error occurred
                        EncryptedDataProto.EncryptedData.newBuilder().clear().build()
                    }
                } else {
                    // 3. If all else fails, return empty KV
                    Log.e("EncryptedKV", "Corruption: Error no backup file")
                    EncryptedDataProto.EncryptedData.newBuilder().clear().build()
                }
            }
        )


        val fileRef = storageLocation(dataStoreName)

        val cfg = singletonEncDataStoreMap[fileRef.path]
            ?: run {
                val newCfg = EncDsConfig(
                    dataStore = DataStoreFactory.create(
                        serializer = EncryptedDataSerializer,
                        corruptionHandler = corruptionHandler,
                        produceFile = { fileRef },
                        scope = dataStoreScope
                    ),
                    dataStoreName = dataStoreName,
                    storageLocation = storageLocation,
                    backupDelayDuration = backupDelayDuration,
                    backupLocation = backupLocation,
                    inDelayPeriod = AtomicBoolean(false)
                )
                singletonEncDataStoreMap[fileRef.path] = newCfg
                return@run newCfg
            }

        dataStore = cfg.dataStore
        pDataStoreName = cfg.dataStoreName
        pBackupDelayDuration = cfg.backupDelayDuration
        pBackupLocation = cfg.backupLocation
        pBackupScope = backupScope
        inDelayPeriod = cfg.inDelayPeriod
    }

    fun deleteBackupFile() {
        backupFile.delete()
    }

    suspend fun createBackupInternal() {
        try {
            val data = dataStore.data.first()
            val map = data.keyValuesMap.mapKeys { it.key }
                .mapValues { Base64.encodeToString(it.value.toByteArray(), Base64.NO_WRAP) }  // Store as base64 for JSON
            val json = Json.encodeToString(map)
            backupFile.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("EncryptedKVDataStore", "Error: Failed creating backup", e)
        }
    }

    private var job: Job? = null

    fun createBackup() {
        pBackupScope?.isActive == true || return
        if (inDelayPeriod.compareAndSet(false, true)) {
            job = pBackupScope.launch {
                delay(pBackupDelayDuration)
                inDelayPeriod.set(false)
                createBackupInternal()
            }
        }
    }

    suspend fun awaitLastBackup() {
        job?.join()
    }

    override val preferencesFlow: Flow<Map<String, String>> = dataStore.data.map { data ->
        data.keyValuesMap.mapValues { (_, encryptedBytes) ->
            runCatching { String(cryptoManager.decrypt(encryptedBytes.toByteArray()), Charsets.UTF_8) }.getOrElse { "Decryption failed" }
        }
    }

    override val backupFile: File
        get() = pBackupLocation(pDataStoreName + BACKUP_FILE_EXT)

    override suspend fun putString(key: String, value: String) {
        val encryptedBytes = cryptoManager.encrypt(value.toByteArray(Charsets.UTF_8))
        dataStore.updateData { current ->
            current.toBuilder().putKeyValues(key, ByteString.copyFrom(encryptedBytes)).build()
        }
        createBackup()
    }

    override suspend fun getString(key: String): String? {
        val data = dataStore.data.first()
        val encryptedBytes = data.keyValuesMap[key]?.toByteArray() ?: return null
        return runCatching {
            String(cryptoManager.decrypt(encryptedBytes), Charsets.UTF_8)
        }.getOrElse { e ->
            Log.e("EncryptedKVDataStore", "Error decrypting value", e)
            null
        }
    }

    override fun flowString(key: String): Flow<String?> {
        return dataStore.data.map { data ->
            data.keyValuesMap[key]?.toByteArray()?.let { encryptedBytes ->
                runCatching {
                    String(cryptoManager.decrypt(encryptedBytes), Charsets.UTF_8)
                }.getOrElse { e ->
                    Log.e("EncryptedKVDataStore", "Error decrypting value", e)
                    null
                }
            }
        }
    }

    override suspend fun remove(key: String) {
        dataStore.updateData { current ->
            current.toBuilder().removeKeyValues(key).build()
        }
        createBackup()
    }

    override suspend fun clearAll() {
        job?.cancel()
        dataStore.updateData { EncryptedDataProto.EncryptedData.getDefaultInstance() }
        deleteBackupFile()
    }

    suspend inline fun <reified T> putValue(key: String, value: T) {
        val json = Json.encodeToString(value)
        putString(key, json)
    }

    suspend inline fun <reified T> getValue(key: String): T? {
        val serializer = serializer<T>()
        return getString(key)?.let { Json.decodeFromString(serializer, it) }
    }

    inline fun <reified T> flowValue(key: String): Flow<T?> {
        val serializer = serializer<T>()
        return flowString(key).map { it?.let { Json.decodeFromString(serializer, it) } }
    }
}
