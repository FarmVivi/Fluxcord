package fr.farmvivi.fluxcord.core.storage.binary;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.storage.binary.file.FileBinaryStorage;
import fr.farmvivi.fluxcord.core.storage.binary.s3.S3BinaryStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Builds the binary (file) storage from {@code data.binary.storage.*}: FILE, or S3 with an optional fallback to
 * FILE when the bucket cannot be reached at boot.
 */
public final class BinaryStorageFactory {
    private static final Logger logger = LoggerFactory.getLogger(BinaryStorageFactory.class);

    private BinaryStorageFactory() {
    }

    /**
     * @throws IllegalStateException when the S3 backend fails and {@code fallback} is off
     */
    public static BinaryStorageManager createBinaryStorageManager(CoreSettings.BinaryStorage settings, EventManager eventManager) {
        if (settings.type() == CoreSettings.BinaryBackend.S3) {
            CoreSettings.S3 s3 = settings.s3();
            try {
                S3BinaryStorage s3Storage = new S3BinaryStorage("s3", s3.bucket(), s3.prefix(), s3.endpoint(),
                        s3.region(), s3.accessKey(), s3.secretKey(), s3.pathStyleAccess(), eventManager);
                logger.info("Using S3 binary storage with bucket {}", s3.bucket());
                return new SimpleBinaryStorageManager(s3Storage);
            } catch (Exception e) {
                logger.error("Failed to initialize S3 binary storage: {}", e.getMessage());
                if (!settings.fallback()) {
                    throw new IllegalStateException("S3 binary storage initialization failed and fallback is disabled", e);
                }
                logger.info("Falling back to file binary storage");
            }
        }
        return createFileBinaryStorageManager(settings, eventManager);
    }

    private static BinaryStorageManager createFileBinaryStorageManager(CoreSettings.BinaryStorage settings, EventManager eventManager) {
        File binaryFolder = settings.fileFolder();
        if (!binaryFolder.exists() && !binaryFolder.mkdirs()) {
            logger.warn("Failed to create binary folder: {}", binaryFolder.getAbsolutePath());
        }
        FileBinaryStorage fileBinaryStorage = new FileBinaryStorage("file", binaryFolder, eventManager);
        logger.info("Using file binary storage in {}", binaryFolder.getAbsolutePath());
        return new SimpleBinaryStorageManager(fileBinaryStorage);
    }
}
