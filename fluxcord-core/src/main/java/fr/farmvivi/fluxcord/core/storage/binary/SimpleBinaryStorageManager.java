package fr.farmvivi.fluxcord.core.storage.binary;

import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorage;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager;
import fr.farmvivi.fluxcord.api.storage.binary.ScopedBinaryStorage;

import java.util.Objects;

/** {@link BinaryStorageManager} over one {@link BinaryStorage} backend (FILE or S3, chosen by {@link BinaryStorageFactory}). */
public class SimpleBinaryStorageManager implements BinaryStorageManager {
    private final BinaryStorage storage;

    public SimpleBinaryStorageManager(BinaryStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public ScopedBinaryStorage getGlobalStorage() {
        return new ScopedBinaryStorage(storage, StorageKey.globalScope());
    }

    @Override
    public ScopedBinaryStorage getUserStorage(String userId) {
        return new ScopedBinaryStorage(storage, StorageKey.userScope(userId));
    }

    @Override
    public ScopedBinaryStorage getGuildStorage(String guildId) {
        return new ScopedBinaryStorage(storage, StorageKey.guildScope(guildId));
    }

    @Override
    public ScopedBinaryStorage getUserGuildStorage(String userId, String guildId) {
        return new ScopedBinaryStorage(storage, StorageKey.userGuildScope(userId, guildId));
    }

    @Override
    public boolean close() {
        return storage.close();
    }
}
