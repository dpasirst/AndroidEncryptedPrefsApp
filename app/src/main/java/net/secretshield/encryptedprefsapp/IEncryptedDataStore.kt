package net.secretshield.encryptedprefsapp

import kotlinx.coroutines.flow.Flow
import java.io.File

interface IEncryptedDataStore {
    val preferencesFlow: Flow<Map<String, String>>
    val backupFile: File
    suspend fun putString(key: String, value: String)
    suspend fun getString(key: String): String?
    fun flowString(key: String): Flow<String?>
    suspend fun remove(key: String)
    suspend fun clearAll()
}
