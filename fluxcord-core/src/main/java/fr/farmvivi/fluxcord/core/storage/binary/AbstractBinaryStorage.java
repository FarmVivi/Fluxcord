package fr.farmvivi.fluxcord.core.storage.binary;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorage;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageKey;
import fr.farmvivi.fluxcord.api.storage.binary.events.FileDeleteEvent;
import fr.farmvivi.fluxcord.api.storage.binary.events.FileDownloadEvent;
import fr.farmvivi.fluxcord.api.storage.binary.events.FileUploadEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Abstract implementation of BinaryStorage with common functionality.
 */
public abstract class AbstractBinaryStorage implements BinaryStorage {
    protected static final Logger logger = LoggerFactory.getLogger(AbstractBinaryStorage.class);

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** Content type by lower-case file extension; anything else is {@link #DEFAULT_CONTENT_TYPE}. */
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            // Images
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            // Audio
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"), Map.entry("ogg", "audio/ogg"),
            Map.entry("flac", "audio/flac"),
            // Video
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("avi", "video/x-msvideo"),
            Map.entry("mov", "video/quicktime"),
            // Documents
            Map.entry("pdf", "application/pdf"), Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            // Text
            Map.entry("txt", "text/plain"), Map.entry("html", "text/html"), Map.entry("htm", "text/html"),
            Map.entry("css", "text/css"), Map.entry("js", "application/javascript"),
            Map.entry("json", "application/json"), Map.entry("xml", "application/xml"),
            // Archives
            Map.entry("zip", "application/zip"), Map.entry("gz", "application/gzip"),
            Map.entry("gzip", "application/gzip"), Map.entry("tar", "application/x-tar"),
            Map.entry("rar", "application/x-rar-compressed"), Map.entry("7z", "application/x-7z-compressed"));
    protected static final int BUFFER_SIZE = 8192;
    protected final String storageName;
    protected final EventManager eventManager;

    /**
     * Creates a new abstract binary storage.
     *
     * @param storageName  the name of this storage
     * @param eventManager the event manager, or null if events should not be fired
     */
    protected AbstractBinaryStorage(String storageName, EventManager eventManager) {
        this.storageName = storageName;
        this.eventManager = eventManager;
    }

    @Override
    public boolean saveFile(BinaryStorageKey key, File file, boolean overwrite) {
        if (!file.exists() || !file.isFile()) {
            logger.error("[{}] Cannot save non-existent file: {}", storageName, file.getAbsolutePath());
            return false;
        }

        // Fire pre-upload event
        if (eventManager != null) {
            FileUploadEvent event = new FileUploadEvent(key, file, overwrite);
            eventManager.fireEvent(event);

            if (event.isCancelled()) {
                logger.debug("[{}] File upload was cancelled by an event listener", storageName);
                return false;
            }
        }

        try (InputStream is = new FileInputStream(file)) {
            return saveFile(key, is, overwrite);
        } catch (IOException e) {
            logger.error("[{}] Error reading file {}: {}", storageName, file.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean saveFile(BinaryStorageKey key, InputStream inputStream, boolean overwrite) {
        // File exists check handled by implementation

        // Fire pre-upload event
        if (eventManager != null) {
            FileUploadEvent event = new FileUploadEvent(key, inputStream, overwrite);
            eventManager.fireEvent(event);

            if (event.isCancelled()) {
                logger.debug("[{}] File upload was cancelled by an event listener", storageName);
                return false;
            }
        }

        try (OutputStream os = getOutputStream(key, overwrite)) {
            if (os == null) {
                logger.error("[{}] Failed to get output stream for path: {}", storageName, key.getFullPath());
                return false;
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }

            return true;
        } catch (IOException e) {
            logger.error("[{}] Error saving file to path {}: {}",
                    storageName, key.getFullPath(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean downloadFile(BinaryStorageKey key, File destFile) {
        if (!fileExists(key)) {
            logger.error("[{}] File does not exist at path: {}", storageName, key.getFullPath());
            return false;
        }

        // Fire pre-download event
        if (eventManager != null) {
            FileDownloadEvent event = new FileDownloadEvent(key, destFile);
            eventManager.fireEvent(event);

            if (event.isCancelled()) {
                logger.debug("[{}] File download was cancelled by an event listener", storageName);
                return false;
            }
        }

        try {
            // Create parent directories if they don't exist
            File parentDir = destFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    logger.error("[{}] Failed to create parent directories for: {}",
                            storageName, destFile.getAbsolutePath());
                    return false;
                }
            }

            Optional<InputStream> isOpt = getInputStream(key);
            if (isOpt.isEmpty()) {
                logger.error("[{}] Failed to get input stream for path: {}", storageName, key.getFullPath());
                return false;
            }

            try (InputStream is = isOpt.get()) {
                Files.copy(is, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                return true;
            }
        } catch (IOException e) {
            logger.error("[{}] Error downloading file from path {} to {}: {}",
                    storageName, key.getFullPath(), destFile.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deleteFile(BinaryStorageKey key) {
        if (!fileExists(key)) {
            return false;
        }

        // Fire pre-delete event
        if (eventManager != null) {
            FileDeleteEvent event = new FileDeleteEvent(key);
            eventManager.fireEvent(event);

            if (event.isCancelled()) {
                logger.debug("[{}] File deletion was cancelled by an event listener", storageName);
                return false;
            }
        }

        boolean result = doDeleteFile(key);

        return result;
    }

    @Override
    public String getContentType(BinaryStorageKey key) {
        String path = key.path();

        // Check cache first
        return determineContentType(path);
    }

    /**
     * Determines the content type based on file extension.
     *
     * @param path the file path
     * @return the content type
     */
    protected String determineContentType(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return DEFAULT_CONTENT_TYPE;
        }
        return CONTENT_TYPES.getOrDefault(path.substring(dot + 1).toLowerCase(), DEFAULT_CONTENT_TYPE);
    }

    /**
     * Lists files in a directory and caches the result.
     * Default implementation returns an empty list.
     *
     * @param key the directory key
     * @return a list of file paths
     */
    @Override
    public List<String> listFiles(BinaryStorageKey key) {
        return Collections.emptyList();
    }

    /**
     * Gets a public URL for a file.
     * Default implementation returns an empty Optional.
     *
     * @param key      the file key
     * @param expireIn the number of seconds until the URL expires
     * @return an Optional containing the URL
     */
    @Override
    public Optional<String> getPublicUrl(BinaryStorageKey key, int expireIn) {
        return Optional.empty();
    }

    /**
     * Performs the actual file deletion operation.
     * Implementations should override this method to delete the file.
     *
     * @param key the key of the file to delete
     * @return true if the deletion was successful
     */
    protected abstract boolean doDeleteFile(BinaryStorageKey key);

    @Override
    public String toString() {
        return "BinaryStorage[" + storageName + "]";
    }
}