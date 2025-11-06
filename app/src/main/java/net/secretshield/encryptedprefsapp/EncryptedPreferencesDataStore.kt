@file:Suppress("unused")
package net.secretshield.encryptedprefsapp

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


/**
 * EncryptedPreferencesDataStore is a factory initializer
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
class EncryptedPreferencesDataStore(
    private val context: Context?,
    dataStoreName: String = DEFAULT_DATASTORE_NAME,
    storageLocation: ((String) -> File) = { fileName ->
        context ?: throw Exception("Context is required for default initialization")
        context.preferencesDataStoreFile(fileName)
    },
    backupDelayDuration: Duration = DEFAULT_BACKUP_DELAY_DURATION,
    backupLocation: ((String) -> File) = { fileName ->
        context ?: throw Exception("Context is required for default initialization")
        File(context.filesDir, fileName)
    },
    backupScope: CoroutineScope? = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    /**
     * Encrypted DataStore configuration. We track this because we may only have a single
     * instance pointing to a single location of a DataStore. This is used to keep all
     * the necessary information available so that if a matching `storageLocation(dataStoreName)`
     * is given, the existing instance will be returned.
     */
    internal data class EncDsConfig(
        val dataStore: DataStore<Preferences>,
        val dataStoreName: String,
        val storageLocation: (String) -> File,
        val backupDelayDuration: Duration,
        val backupLocation: (String) -> File,
        val inDelayPeriod: AtomicBoolean
    )

    companion object {
        private const val DEFAULT_DATASTORE_NAME = "encrypted_preferences"
        internal const val BACKUP_FILE_EXT = ".backup.json"
        private val DEFAULT_BACKUP_DELAY_DURATION = 10.seconds

        // we do not offer a way to clean up or close open DataStores because
        // there is no way to do so
        internal var singletonEncDataStoreMap: ConcurrentMap<String, EncDsConfig> =
            ConcurrentHashMap()
    }

    val dataStore: DataStore<Preferences>
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

                // 1. Backup the corrupt file
//                val corruptFile = storageLocation(dataStoreName)
//                if (corruptFile.exists()) {
//                    val backupCorruptFile = File(
//                        corruptFile.parentFile,
//                        "corrupt_backup_${System.currentTimeMillis()}.preferences_pb"
//                    )
//                    corruptFile.copyTo(backupCorruptFile)
//                }

                // 2. Try to restore from backup
                if (backupFile.exists()) {
                    try {
                        val backupJson = backupFile.readText()
                        val backupData: Map<String, String> = Json.decodeFromString(backupJson)

                        val mutablePreferences = emptyPreferences().toMutablePreferences()
                        backupData.forEach { (key, value) ->
                            val preferencesKey = stringPreferencesKey(key)
                            mutablePreferences[preferencesKey] = value // value is encrypted
                        }
                        mutablePreferences.toPreferences()
                    } catch (e: Exception) {
                        Log.e("EncryptedPreferences", "Corruption: Error restoring from backup", e)
                        // Backup is corrupt or another error occurred
                        emptyPreferences()
                    }
                } else {
                    // 3. If all else fails, return empty preferences
                    Log.e("EncryptedPreferences", "Corruption: Error no backup file")
                    emptyPreferences()
                }
            }
        )
        val fileRef = storageLocation(dataStoreName)

        val cfg = singletonEncDataStoreMap[fileRef.path]
            ?: run {
                val newCfg = EncDsConfig(
                    dataStore = PreferenceDataStoreFactory.create(
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

    internal val backupFile: File //backupLocation(dataStoreName + BACKUP_FILE_EXT)
        get() = pBackupLocation(pDataStoreName + BACKUP_FILE_EXT)

    fun deleteBackupFile() {
        backupFile.delete()
    }

    suspend fun createBackupInternal() {
        try {
            val preferences = dataStore.data.first()
            val map = preferences.asMap()
                .filterValues { it is String } // Ensure we only backup string preferences
                .mapKeys { it.key.name }
                .mapValues { it.value as String }
            val json = Json.encodeToString(map)
            backupFile.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            // Handle backup creation error, e.g., log it
            Log.e("EncryptedPreferences", "Error: Failed creating backup", e)
        }
    }

    private var job: Job? = null

    /**
     * this is intended to be internal/private only.
     * do not call this from outside the class
     * it has to be public because internally we use `reified`
     * with public functions
     *
     * How this works: We don't want to write every time a change is made. Instead,
     * we allow change multiple changes to be committed to the datastore a given amount
     * of time after the first change is written the backup will be triggered capturing
     * all the subsequent changes.
     */
    fun createBackup() {
        // if we were passed a scope that is already null, expired, or cancelled then we skip this
        pBackupScope?.isActive == true || return
        if (inDelayPeriod.compareAndSet(false, true)) {
            job = pBackupScope.launch {
                delay(pBackupDelayDuration)
                // we set false here before calling `createBackupInternal()` that way
                // if there is a race, we don't fail to backup a value. Worst case, it will
                // trigger a second backup for a value that made it into this one.
                inDelayPeriod.set(false)
                createBackupInternal()
            }
        }
    }

    /**
     * this is intended to be internal/private only.
     * do not call this from outside the class. It is used by the test class.
     *
     * If an active backup job is pending it will wait for it to complete
     */
    suspend fun awaitLastBackup() {
        job?.join()
    }

    /**
     * Flow that emits all preferences as Map<String, String> (decrypted)
     * This will decrypt values but it will **not** attempt to decode (deserialize) values
     */
    val preferencesFlow: Flow<Map<String, String>> = dataStore.data.map { preferences ->
        preferences.asMap().mapKeys { it.key.name }.mapValues { (it.value as? String)?.let { encrypted ->
            runCatching { cryptoManager.decryptToString(encrypted) }.getOrElse { "Decryption failed" }
        } ?: "" }
    }


    /**
     * Will put (aka set) the value by encrypting and storing it for a given key.
     */
    @Throws(Exception::class)
    suspend fun putString(key: String, value: String) {
        val preferencesKey = stringPreferencesKey(key)
        val encryptedValue = cryptoManager.encryptToString(value)
        dataStore.edit { it[preferencesKey] = encryptedValue }
        createBackup()
    }

    /**
     * Will provide a decrypted value for a given key.
     */
    suspend fun getString(key: String): String? {
        val preferencesKey = stringPreferencesKey(key)
        val encryptedValue = try {
            val preferences = dataStore.data.first()
            preferences[preferencesKey] ?: return null
        } catch (e: Exception) {
            Log.e("EncryptedPreferences", "Error: Failed reading value", e)
            return null
        }
        return try {
            cryptoManager.decryptToString(encryptedValue)
        } catch (e: Exception) {
            // Handle decryption or deserialization error
            Log.e("EncryptedPreferences", "Error: Failed decrypting value", e)
            null
        }
    }

    /**
     * Will provide a decrypted value flow for a given key. Any time a new value `putString(...)`
     * is called, the flow will emit the new value.
     *
     * @param key is the key to retrieve the value for
     * @return a flow of the decrypted value.
     *
     * ```kotlin
     * dataStore.flowString(key).collect { values.add(it) }
     * ```
     */
    fun flowString(key: String): Flow<String?> {
        val preferencesKey = stringPreferencesKey(key)
        return dataStore.data.map { preferences ->
            preferences[preferencesKey]?.let { encryptedValue ->
                try {
                    cryptoManager.decryptToString(encryptedValue)
                } catch (e: Exception) {
                    // Handle decryption or deserialization error
                    Log.e("EncryptedPreferences", "Error: Failed decrypting value", e)
                    null
                }
            }
        }
    }

    /**
     * Will put (aka set) the value by encrypting and storing it for a given key.
     *
     * This requires a serializable type using `kotlinx.serialization`.
     *
     */
    @Throws(Exception::class)
    suspend inline fun <reified T> putValue(key: String, value: T) {
        val serializer = serializer<T>()
        val preferencesKey = stringPreferencesKey(key)
        val jsonString = Json.encodeToString(serializer, value)
        val encryptedValue = cryptoManager.encryptToString(jsonString)
        dataStore.edit { it[preferencesKey] = encryptedValue }
        createBackup()
    }

    /**
     * Will provide a decrypted value for a given key.
     */
    suspend inline fun <reified T> getValue(key: String): T? {
        val serializer = serializer<T>()
        val preferencesKey = stringPreferencesKey(key)
        val encryptedValue = try {
            val preferences = dataStore.data.first()
            preferences[preferencesKey] ?: return null
        } catch (e: Exception) {
            Log.e("EncryptedPreferences", "Error: Failed reading value", e)
            return null
        }
        return try {
            val decryptedJson = cryptoManager.decryptToString(encryptedValue)
            Json.decodeFromString(serializer, decryptedJson)
        } catch (e: Exception) {
            // Handle decryption or deserialization error
            Log.e("EncryptedPreferences", "Error: Failed decrypting value", e)
            null
        }
    }

    /**
     * Will provide a decrypted value flow for a given key. Any time a new value `putValue(...)`
     * is called, the flow will emit the new value.
     *
     * This requires a serializable type using `kotlinx.serialization`.
     *
     * @param key is the key to retrieve the value for
     * @return a flow of the decrypted value.
     *
     * ```kotlin
     * dataStore.flowValue<SomeSerializableType>(key).collect { values.add(it) }
     * ```
     */
    inline fun <reified T> flowValue(key: String): Flow<T?> {
        val serializer = serializer<T>()
        val preferencesKey = stringPreferencesKey(key)
        return dataStore.data.map { preferences ->
            preferences[preferencesKey]?.let { encryptedValue ->
                try {
                    val decryptedJson = cryptoManager.decryptToString(encryptedValue)
                    Json.decodeFromString(serializer, decryptedJson)
                } catch (e: Exception) {
                    // Handle decryption or deserialization error
                    Log.e("EncryptedPreferences", "Error: Failed decrypting or decoding value", e)
                    null
                }
            }
        }
    }

    /**
     * will remove the key value from the datastore and notify that a backup is required
     */
    suspend fun remove(key: String) {
        val preferencesKey = stringPreferencesKey(key)
        dataStore.edit { it.remove(preferencesKey) }
        createBackup()
    }

    /**
     * Clear all preferences and deletes the backup file containing the last backup
     * of the preferences.
     */
    suspend fun clearAll() {
        job?.cancel()
        dataStore.edit { it.clear() }
        deleteBackupFile()
    }
}
