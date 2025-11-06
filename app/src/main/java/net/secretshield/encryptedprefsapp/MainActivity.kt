package net.secretshield.encryptedprefsapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.secretshield.encryptedprefsapp.ui.theme.EncryptedPrefsAppTheme

data class PreferenceItem(val key: String, val value: String)

class MainActivity : ComponentActivity() {
    private val viewModel: PreferencesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EncryptedPrefsAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PreferencesScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun PreferencesScreen(
    viewModel: PreferencesViewModel,
    modifier: Modifier = Modifier
) {
    val preferences by viewModel.preferencesState.collectAsState()
    val backupTs by viewModel.backupFileTs.collectAsState()

    val scope = rememberCoroutineScope()

    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Add new preference section
        Text(
            text = "Manage Preferences",
            style = MaterialTheme.typography.headlineSmall
        )
        OutlinedTextField(
            value = newKey,
            onValueChange = { newKey = it },
            label = { Text("Key") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = newValue,
            onValueChange = { newValue = it },
            label = { Text("Value") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = {
                    if (newKey.isNotBlank() && newValue.isNotBlank()) {
                        viewModel.addPreference(newKey, newValue)
                        newKey = ""
                        newValue = ""
                    }
                }
            ) {
                Text("Add Preference")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (newKey.isNotBlank()) {
                        scope.launch {
                            val retrievedValue = viewModel.getPreference(newKey)
                            if (retrievedValue != null) {
                                newValue = retrievedValue
                            }
                        }
                    }
                }
            ) {
                Text("Get Preference")
            }
        }

        Spacer(modifier = Modifier.padding(16.dp))

        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = {
                    viewModel.deleteAllPreferences()
                }
            ) {
                Text("Delete Preference")
            }
        }
        Spacer(modifier = Modifier.padding(16.dp))
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Text(
                text = "Backup File TS: $backupTs",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        Spacer(modifier = Modifier.padding(16.dp))

        // Display all preferences
        Text(
            text = "Stored Preferences",
            style = MaterialTheme.typography.headlineSmall
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(preferences.toList()) { (key, value) ->
                PreferenceListItem(
                    item = PreferenceItem(key, value),
                    onRemove = { viewModel.removePreference(it) }
                )
            }
        }

    }
}

@Composable
fun PreferenceListItem(item: PreferenceItem, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Key: ${item.key}", style = MaterialTheme.typography.bodyLarge)
            Text(text = "Value: ${item.value}", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { onRemove(item.key) }) {
            Text("Delete")
        }
    }
}
