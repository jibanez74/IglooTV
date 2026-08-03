package com.igloo.blindpenguincoder.core.storage

class FakeProfileStore(var vault: ProfileVault = ProfileVault()) : ProfileStore {

    override suspend fun read(): ProfileVault = vault

    override suspend fun update(transform: (ProfileVault) -> ProfileVault): ProfileVault {
        vault = transform(vault)
        return vault
    }
}
