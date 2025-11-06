package net.secretshield.encryptedprefsapp

import android.app.Application
import android.os.Build
import android.os.FileObserver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

enum class DataStoreMode { PREF, KV }

class PreferencesViewModel(application: Application) : AndroidViewModel(application) {

    val currentMode = MutableStateFlow(DataStoreMode.PREF)

    private val initialRepo = EncryptedPreferencesDataStore(application)

    val currentRepository: StateFlow<IEncryptedDataStore> = currentMode
        .map { mode ->
            when (mode) {
                DataStoreMode.PREF -> EncryptedPreferencesDataStore(application)
                DataStoreMode.KV -> EncryptedKVDataStore(application)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialRepo)

    @OptIn(ExperimentalCoroutinesApi::class)
    val preferencesState = currentRepository
        .flatMapLatest { it.preferencesFlow }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val backupFileTs = currentRepository
        .flatMapLatest { fileModificationFlow(it.backupFile) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun setMode(mode: DataStoreMode) {
        currentMode.value = mode
    }

    fun addPreference(key: String, value: String) {
        viewModelScope.launch {
            currentRepository.value.putString(key, value)
        }
    }

    suspend fun getPreference(key: String): String? {
        return currentRepository.value.getString(key)
    }

    fun removePreference(key: String) {
        viewModelScope.launch {
            currentRepository.value.remove(key)
        }
    }

    fun deleteAllPreferences() {
        viewModelScope.launch {
            currentRepository.value.clearAll()
        }
    }

    /**
     * Purpose: to watch a file location such that a file may be created, updated, or deleted and
     * then report on the last modified time. If the file does not exist, it will return 0
     * This function can be called before the file is created. The file can be deleted and
     * recreated as well.
     *
     * @param file the file location to watch
     *
     * @return callbackFlow<Long> representing the last modified time of the file
     */
    fun fileModificationFlow(file: File) = callbackFlow {
        val parentDir = file.parentFile?.path
        if (parentDir == null) {
            close(IllegalArgumentException("File must have a parent directory"))
            return@callbackFlow
        }

        // Emit current state immediately
        trySend(if (file.exists()) file.lastModified() else 0L)


        val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(File(parentDir), ALL_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path != file.name) return

                    when (event) {
                        CREATE, MODIFY, CLOSE_WRITE, MOVED_TO -> {
                            val timestamp = if (file.exists()) file.lastModified() else 0L
                            trySend(timestamp)
                        }
                        DELETE, MOVED_FROM -> {
                            if (!file.exists()) trySend(0L)
                        }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(parentDir, ALL_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path != file.name) return

                    when (event) {
                        CREATE, MODIFY, CLOSE_WRITE, MOVED_TO -> {
                            val timestamp = if (file.exists()) file.lastModified() else 0L
                            trySend(timestamp)
                        }
                        DELETE, MOVED_FROM -> {
                            if (!file.exists()) trySend(0L)
                        }
                    }
                }
            }
        }

        observer.startWatching()

        awaitClose {
            observer.stopWatching()
        }
    }
}
