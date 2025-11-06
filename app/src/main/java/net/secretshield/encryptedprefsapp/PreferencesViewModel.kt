package net.secretshield.encryptedprefsapp

import android.app.Application
import android.os.Build
import android.os.FileObserver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

class PreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EncryptedPreferencesDataStore(application)

    val preferencesState = repository.preferencesFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyMap()
    )

    val backupFileTs = fileModificationFlow(repository.backupFile).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    fun addPreference(key: String, value: String) {
        viewModelScope.launch {
            repository.putString(key, value)
        }
    }

    suspend fun getPreference(key: String): String? {
        return repository.getString(key)
    }

    fun removePreference(key: String) {
        viewModelScope.launch {
            repository.remove(key)
        }
    }

    fun deleteAllPreferences() {
        viewModelScope.launch {
            repository.clearAll()
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
