package fr.farmvivi.fluxcord.core.storage;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.storage.db.DatabaseDataStorage;
import fr.farmvivi.fluxcord.core.storage.file.FileDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Builds the key/value storage from {@code data.storage.*}: FILE, or DB with an optional fallback to FILE when
 * the database cannot be reached at boot.
 */
public final class StorageFactory {
    private static final Logger logger = LoggerFactory.getLogger(StorageFactory.class);

    private StorageFactory() {
    }

    /**
     * @throws IllegalStateException when the DB backend fails and {@code fallback} is off
     */
    public static DataStorageManager createStorageManager(CoreSettings.DataStorage settings, EventManager eventManager) {
        if (settings.type() == CoreSettings.DataBackend.DB) {
            try {
                DatabaseDataStorage dbStorage = new DatabaseDataStorage(settings.database(), eventManager);
                logger.info("Using database storage");
                return new SimpleDataStorageManager(dbStorage);
            } catch (Exception e) {
                logger.error("Failed to initialize database storage: {}", e.getMessage());
                if (!settings.fallback()) {
                    throw new IllegalStateException("Database storage initialization failed and fallback is disabled", e);
                }
                logger.info("Falling back to file storage");
            }
        }
        return createFileStorageManager(settings, eventManager);
    }

    private static DataStorageManager createFileStorageManager(CoreSettings.DataStorage settings, EventManager eventManager) {
        File storageFolder = new File(settings.fileFolder(), "storage");
        if (!storageFolder.exists() && !storageFolder.mkdirs()) {
            logger.warn("Failed to create storage folder: {}", storageFolder.getAbsolutePath());
        }
        FileDataStorage fileStorage = new FileDataStorage(storageFolder, eventManager, settings.debounceMs());
        logger.info("Using file storage in {}", storageFolder.getAbsolutePath());
        return new SimpleDataStorageManager(fileStorage);
    }
}
