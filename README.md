# Encrypted Prefs DataStore Android App Demo

This is a demo exploring the use of Android's DataStore
for storing encrypted data such as preferences.

## Why

Google has deprecated the Crypto Jetpack offering EncryptedPreferences
and instead recommends using DataStore. Google has also indicated
that DataStore is preferred over SharedPreferences which is likely to be
deprecated in the future as well.

## What this does

This is an Android demo app to explore encrypted DataStore options.

This encrypts values before storing them in the DataStore and decrypts upon retrieval.
It fully supports DataStore features such as using Flow.

This offers 2 implementations of using DataStore:

1. Using the `datastore-prefs`. In this case, all data is encrypted and then 
Base64 encoded to a string that is stored as the value.
2. Using a `protobuf` based `datastore`. In this case, all data is encrypted and then
encoded to a protobuf ByteString that is packed into the protobuf object then stored.

### How is corruption handled

Both implementations offer the efficient creation of a backup file in a json
format with the encrypted data that can be used to restore a corrupt DataStore. The
implementation also provides for a corruption handler that will automatically
use the file.

## App Features

The app uses Strings for both Keys and Values; however, the underlying Encrypted 
DataStore.

- Dynamically switch between the DataStore implementations.
- See the backup timestamps (~10 second delay)
- put, get, and flow of stored Key/Values
- Wipe/Clear to reset all data

## Encrypted DataStore Features

- Encryption uses the Android `KeyStore`
- `TEE` or `StrongBox` encryption.
- flow all objects as raw strings (may not be what you want)
- put, get, and flow for a given key of either a String (native), or as 
a Serializable Value (primitive or any object) using `kotlinx.serialization`
- Automatic efficient backup of the DataStore
- Automatic restore of a corrupt DataStore


## Tests

While all the tests pass, some of the tests only pass when run individually.
It is unclear as to why as there is no obvious race condition that would cause
the issue.

## License and Copyright

MIT License

Copyright (c) 2025 David Pasirstein
