package com.igloo.blindpenguincoder.core.storage

class FakeDeviceTokenStore(var stored: String? = null) : DeviceTokenStore {
    override suspend fun read(): String? = stored

    override suspend fun write(token: String) {
        stored = token
    }

    override suspend fun clear() {
        stored = null
    }
}
