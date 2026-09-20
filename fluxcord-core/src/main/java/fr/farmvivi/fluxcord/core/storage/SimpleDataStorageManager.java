package fr.farmvivi.fluxcord.core.storage;

import fr.farmvivi.fluxcord.api.storage.DataStorage;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import fr.farmvivi.fluxcord.api.storage.StorageKey;

import java.util.Objects;

/** {@link DataStorageManager} over one {@link DataStorage} backend (FILE or DB, chosen by {@link StorageFactory}). */
public class SimpleDataStorageManager implements DataStorageManager {
    private final DataStorage storage;

    public SimpleDataStorageManager(DataStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public ScopedStorage getGlobalStorage() {
        return new ScopedStorage(storage, StorageKey.globalScope());
    }

    @Override
    public ScopedStorage getUserStorage(String userId) {
        return new ScopedStorage(storage, StorageKey.userScope(userId));
    }

    @Override
    public ScopedStorage getGuildStorage(String guildId) {
        return new ScopedStorage(storage, StorageKey.guildScope(guildId));
    }

    @Override
    public ScopedStorage getUserGuildStorage(String userId, String guildId) {
        return new ScopedStorage(storage, StorageKey.userGuildScope(userId, guildId));
    }

    @Override
    public boolean saveAll() {
        return storage.save();
    }

    @Override
    public boolean close() {
        return storage.close();
    }
}
