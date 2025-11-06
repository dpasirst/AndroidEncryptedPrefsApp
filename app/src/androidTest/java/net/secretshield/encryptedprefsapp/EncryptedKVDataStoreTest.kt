package net.secretshield.encryptedprefsapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.io.path.pathString
import kotlin.text.contentEquals
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class EncryptedKVDataStoreTest {

    private lateinit var dataStore: EncryptedKVDataStore
    private lateinit var context: Context
    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)
    private val testScope = TestScope(testDispatcher + Job())
    private val testDataStoreName = "test_encrypted_kv"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dataStore = EncryptedKVDataStore(
            context = context,
            dataStoreName = testDataStoreName,
            backupDelayDuration = 10.milliseconds,
            backupScope = testScope
        )
    }

    @After
    fun teardown() = runTest {
        dataStore.clearAll()
        testScheduler.advanceUntilIdle()
        testScope.cancel()
        val datastoreFile = File(context.filesDir, testDataStoreName)
        if (datastoreFile.exists()) {
            datastoreFile.delete()
        }
        val backupFile = File(
            context.filesDir,
            "$testDataStoreName${EncryptedKVDataStore.BACKUP_FILE_EXT}"
        )
        if (backupFile.exists()) {
            backupFile.delete()
        }
    }

    @Test
    fun putAndGetString_returnsSavedString() = testScope.runTest {
        val key = "test_key"
        val value = "test_value"

        dataStore.putString(key, value)
        val retrievedValue = dataStore.getString(key)

        assertTrue(value.contentEquals(retrievedValue))
    }

    @Test
    fun putAndGetObject_returnsSavedObject() = testScope.runTest {
        val key = "test_object_key"
        val value = TestData("test", 123)

        dataStore.putValue(key, value)
        val retrievedValue = dataStore.getValue<TestData>(key)

        assertEquals(value, retrievedValue)
    }

    @Test
    fun remove_removesValue() = testScope.runTest {
        val key = "test_key_to_remove"
        val value = "test_value"

        dataStore.putString(key, value)
        dataStore.remove(key)
        val retrievedValue = dataStore.getString(key)

        assertNull(retrievedValue)
    }

    @Test
    fun clearAll_clearsAllValues() = testScope.runTest {
        val key1 = "key1"
        val value1 = "value1"
        val key2 = "key2"
        val value2 = TestData("test", 1)

        dataStore.putString(key1, value1)
        dataStore.putValue(key2, value2)
        dataStore.clearAll()

        assertNull(dataStore.getString(key1))
        assertNull(dataStore.getValue<TestData>(key2))
        assertFalse(dataStore.backupFile.exists())
    }

    @Test
    fun flowValue_emitsStringChanges() = testScope.runTest {
        val key = "flow_test_key"
        val initialValue = "initial"
        val updatedValue = "updated"
        val values = mutableListOf<String?>()
        val job = launch {
            dataStore.flowString(key).collect { values.add(it) }
        }
        testScheduler.advanceUntilIdle() // Ensure collection starts

        dataStore.putString(key, initialValue)
        testScheduler.advanceUntilIdle()
        dataStore.putString(key, updatedValue)
        testScheduler.advanceUntilIdle()

        job.cancel()

        assertEquals(3, values.size)
        assertEquals(null, values[0])
        assertEquals(initialValue, values[1])
        assertEquals(updatedValue, values[2])
    }

    @Test
    fun flowPrimitiveValue1_emitsValueChanges() = testScope.runTest {
        val key = "flow_test_key"
        val initialValue = "initial"
        val updatedValue = "updated"
        val values = mutableListOf<String?>()
        val job = launch {
            dataStore.flowValue<String>(key).collect { values.add(it) }
        }
        testScheduler.advanceUntilIdle() // Ensure collection starts

        dataStore.putValue(key, initialValue)
        testScheduler.advanceUntilIdle()
        dataStore.putValue(key, updatedValue)
        testScheduler.advanceUntilIdle()

        job.cancel()

        assertEquals(3, values.size)
        assertEquals(null, values[0])
        assertEquals(initialValue, values[1])
        assertEquals(updatedValue, values[2])
    }
    @Test
    fun flowPrimitiveValue2_emitsValueChanges() = testScope.runTest {
        val key = "flow_test_key"
        val initialValue = 22L
        val updatedValue = 56L
        val values = mutableListOf<Long?>()
        val job = launch {
            dataStore.flowValue<Long>(key).collect { values.add(it) }
        }
        testScheduler.advanceUntilIdle() // Ensure collection starts

        dataStore.putValue(key, initialValue)
        testScheduler.advanceUntilIdle()
        dataStore.putValue(key, updatedValue)
        testScheduler.advanceUntilIdle()

        job.cancel()

        assertEquals(3, values.size)
        assertEquals(null, values[0])
        assertEquals(initialValue, values[1])
        assertEquals(updatedValue, values[2])
    }

    @Test
    fun flowObjectValue_emitsValueChanges() = testScope.runTest {
        val key = "flow_test_key"
        val initialValue = TestData("initial", 1)
        val updatedValue = TestData("updated", 2)
        val values = mutableListOf<TestData?>()
        val job = launch {
            dataStore.flowValue<TestData>(key).collect { values.add(it) }
        }
        testScheduler.advanceUntilIdle() // Ensure collection starts

        dataStore.putValue(key, initialValue)
        testScheduler.advanceUntilIdle()
        dataStore.putValue(key, updatedValue)
        testScheduler.advanceUntilIdle()

        job.cancel()

        assertEquals(3, values.size)
        assertEquals(null, values[0])
        assertEquals(initialValue.name, values[1]?.name)
        assertEquals(updatedValue.name, values[2]?.name)
    }

    @Test
    fun preferencesFlow_emitsAllPreferences() = testScope.runTest {
        val key1 = "prefs_flow_key1"
        val value1 = "value1"
        val key2 = "prefs_flow_key2"
        val value2 = "value2"
        var lastMap: Map<String, String>? = null
        val job = launch {
            dataStore.preferencesFlow.collect { lastMap = it }
        }
        testScheduler.advanceUntilIdle() // Ensure collection starts

        dataStore.putString(key1, value1)
        testScheduler.advanceUntilIdle()
        dataStore.putString(key2, value2)
        testScheduler.advanceUntilIdle()

        testScheduler.advanceUntilIdle()

        assertNotNull(lastMap)
        assertTrue(lastMap?.get(key1) == value1)
        assertTrue(lastMap?.get(key2) == value2)

        job.cancel()
    }


    @Test
    fun backup_isCreatedAfterPut() = testScope.runTest {
        dataStore.deleteBackupFile() // Ensure no backup from previous tests

        dataStore.putString("backup_test", "value")

        testScheduler.advanceUntilIdle()

        testScheduler.advanceTimeBy(11L.milliseconds) // Advance time to allow the delayed backup to complete
        dataStore.awaitLastBackup()

        assertTrue(dataStore.backupFile.exists())
        val backupContent = dataStore.backupFile.readText()
        assertTrue(backupContent.isNotEmpty())
        assertTrue(backupContent.contains("backup_test"))
    }

    /**
     * if this next test fails but other times succeeds, it is likely due
     * to the creation of the corrupt file. For some reason, the file
     * may not be recognized as corrupt and the corruption handler will
     * not be called.
     *
     * This version corrupts a copy of the datastore file.
     */
    @Test
    fun restoreFromBackup_onCorruption1() = testScope.runTest {
        val key = "corruption_test"
        val value = "value_to_restore"

        // 1. Save data and create a backup
        dataStore.putString(key, value)
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(11L.milliseconds) // Advance time to allow the delayed backup to complete
        dataStore.awaitLastBackup()
        assertTrue(dataStore.backupFile.exists())

        // 2. establish a new datastore instance
        // (we can only have 1 singleton per store and there is no way to close it
        // and reopen the same, so we must create a new one)
        val testDataStoreNameV2 = "$testDataStoreName.v2"
        // 3. we will use the prior backup file to restore db
        val backupFileV2 = File(
            dataStore.backupFile.toPath().parent.pathString
                    + File.separator
                    + testDataStoreNameV2
                    + EncryptedPreferencesDataStore.BACKUP_FILE_EXT
        )

        assertTrue("Fail Rename failed!", dataStore.backupFile.renameTo(backupFileV2))

        // 4. Corrupt the datastore file
        val datastoreFile = File(context.filesDir, testDataStoreNameV2)
        // Overwrite with random bytes to ensure invalid ProtoBuf data (protobuf corruption)
        var corruptBytes = byteArrayOf('\n'.code.toByte())
        corruptBytes += '\n'.code.toByte()
        corruptBytes += Random.nextBytes(1024)
        datastoreFile.writeBytes(corruptBytes)

        // 5. Re-initialize EncryptedKVDataStore to trigger corruption handler
        val newDataStore = EncryptedKVDataStore(context, testDataStoreNameV2, backupScope = CoroutineScope(Dispatchers.IO + SupervisorJob()))
        // Note: No advanceUntilIdle needed as operations use Dispatchers.IO

        // 6. Check if the data was restored from the backup
        val restoredValue = newDataStore.getString(key)
        assertTrue("$value != $restoredValue",value.contentEquals(restoredValue))

        // 7. Cleanup
        newDataStore.clearAll()
        datastoreFile.delete()
        backupFileV2.delete()
    }

    /**
     * if this next test fails but other times succeeds, it is likely due
     * to the creation of the corrupt file. For some reason, the file
     * may not be recognized as corrupt and the corruption handler will
     * not be called.
     *
     * This version corrupts the original datastore file.
     */
    @Test
    fun restoreFromBackup_onCorruption2() = testScope.runTest {
        val key = "corruption_test"
        val value = "value_to_restore"

        // 1. Save data and create a backup
        dataStore.putString(key, value)
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(11L.milliseconds) // Advance time to allow the delayed backup to complete
        dataStore.awaitLastBackup()
        assertTrue(dataStore.backupFile.exists())

        // 2. Corrupt the datastore file
        val datastoreFile = File(context.filesDir, testDataStoreName)
        // Overwrite with random bytes to ensure invalid ProtoBuf data (protobuf corruption)
        var corruptBytes = byteArrayOf('\n'.code.toByte())
        corruptBytes += '\n'.code.toByte()
        corruptBytes += Random.nextBytes(1024)
        datastoreFile.writeBytes(corruptBytes)

        // 3. Force the corruptionHandler to run so we don't get an in memory result for step 4
        dataStore.putString("corruption_test_extra", value)
        // 4. Check if the data was restored from the backup
        val restoredValue = dataStore.getString(key)
        assertTrue("$value != $restoredValue",value.contentEquals(restoredValue))
        testScheduler.advanceTimeBy(5000L.milliseconds)
    }

    @Serializable
    data class TestData(val name: String, val value: Int)
}
