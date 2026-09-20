package fr.farmvivi.fluxcord.api.storage.binary;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A view of a {@link BinaryStorage} restricted to one scope (global, a user, a guild or a user inside a guild)
 * and, optionally, to a directory prefix.
 * <p>
 * Without a namespace the view addresses the scope's paths verbatim. With a namespace {@code n}, a path
 * {@code p} is stored under {@code n/p} and {@link #listFiles(String)} strips the prefix again. Plugins get views
 * namespaced by their id from {@code AbstractPlugin.getPluginBinaryStorage()}.
 */
public final class ScopedBinaryStorage {
    private final BinaryStorage storage;
    private final String scope;
    private final String prefix;

    /**
     * Creates an un-namespaced view of a scope.
     *
     * @param storage the underlying storage
     * @param scope   the scope string (see {@code StorageKey.userScope(String)} and friends)
     */
    public ScopedBinaryStorage(BinaryStorage storage, String scope) {
        this(storage, scope, "");
    }

    private ScopedBinaryStorage(BinaryStorage storage, String scope, String prefix) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.prefix = prefix;
    }

    /**
     * Returns a view of the same scope whose paths live under {@code namespace + "/"}.
     *
     * @param namespace the namespace, typically a plugin id
     * @return the namespaced view
     */
    public ScopedBinaryStorage namespaced(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        return new ScopedBinaryStorage(storage, scope, prefix + namespace + "/");
    }

    /** The scope string this view addresses. */
    public String getScope() {
        return scope;
    }

    /** The path prefix applied by this view ({@code ""} when un-namespaced, {@code "<namespace>/"} otherwise). */
    public String getPrefix() {
        return prefix;
    }

    public boolean saveFile(String path, File file, boolean overwrite) {
        return storage.saveFile(key(path), file, overwrite);
    }

    public boolean saveFile(String path, InputStream inputStream, boolean overwrite) {
        return storage.saveFile(key(path), inputStream, overwrite);
    }

    public OutputStream getOutputStream(String path, boolean overwrite) {
        return storage.getOutputStream(key(path), overwrite);
    }

    public Optional<InputStream> getInputStream(String path) {
        return storage.getInputStream(key(path));
    }

    public boolean downloadFile(String path, File destFile) {
        return storage.downloadFile(key(path), destFile);
    }

    public boolean fileExists(String path) {
        return storage.fileExists(key(path));
    }

    public boolean deleteFile(String path) {
        return storage.deleteFile(key(path));
    }

    /** @return the entries under {@code path}, relative to this view (prefix stripped) */
    public List<String> listFiles(String path) {
        List<String> files = storage.listFiles(key(path));
        if (prefix.isEmpty()) {
            return files;
        }
        return files.stream()
                .map(file -> file.startsWith(prefix) ? file.substring(prefix.length()) : file)
                .toList();
    }

    public long getFileSize(String path) {
        return storage.getFileSize(key(path));
    }

    public long getLastModifiedTime(String path) {
        return storage.getLastModifiedTime(key(path));
    }

    public String getContentType(String path) {
        return storage.getContentType(key(path));
    }

    public boolean createDirectory(String path) {
        return storage.createDirectory(key(path));
    }

    public boolean isDirectory(String path) {
        return storage.isDirectory(key(path));
    }

    public Optional<String> getPublicUrl(String path, int expireIn) {
        return storage.getPublicUrl(key(path), expireIn);
    }

    private BinaryStorageKey key(String path) {
        return new BinaryStorageKey(scope, BinaryStorageKey.normalizePath(prefix + path));
    }
}
