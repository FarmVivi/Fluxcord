---
name: fluxcord-storage
description: Expert on Fluxcord persistence - key/value DataStorage (FILE json or DB via HikariCP with MySQL/MariaDB/PostgreSQL SqlDialect), scoped views (global/user/guild/user-guild) and per-plugin namespacing, plus BinaryStorage (FILE or S3). Use when reading/writing plugin data, changing storage backends, fallback behaviour, caching/save semantics, SQL schema or table prefix, S3 config, or debugging lost/unsaved data.
paths:
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/storage/**
  - fluxcord-api/src/main/java/fr/farmvivi/fluxcord/api/storage/**
---

# Storage

## Key/value data storage
- Contract: `api/storage/DataStorage` — `get(StorageKey, Class<T>)`, `set`, `exists`, `remove`, `getKeys(scope)`, `getAll(scope)`, `clear(scope)`, `save()`, `close()`. `StorageKey(scope, key)` record with factories `global(key)`, `user(userId, key)`, `guild(guildId, key)`, `userGuild(userId, guildId, key)` — scope is a string like `user:<id>`.
- `api/storage/DataStorageManager` is an **interface** (impl `core/storage/SimpleDataStorageManager`, built by `StorageFactory`); `getGlobalStorage()/getUserStorage(id)/getGuildStorage(id)/getUserGuildStorage(u, g)` return one `api/storage/ScopedStorage` (final class: `DataStorage` + scope + key prefix), plus `saveAll()/close()`. Scope strings come from `StorageKey.globalScope()/userScope()/guildScope()/userGuildScope()` — the only place the formats live.
- Plugin namespacing: `PluginDataStorageAdapter` (from `AbstractPlugin.getStorage()`) returns `scopedView.namespaced(pluginId)`: keys are stored as `<pluginId>.<key>` in the same scope; `getKeys()/getAll()` strip the prefix and hide other keys, `clear()` removes only the namespaced keys (an un-namespaced `clear()` drops the whole scope). Core uses unprefixed views (`commands.prefix` in guild scope, `permission.*` in `SimplePermissionManager`).
- Values are serialized with the one shared Gson in `core/storage/StorageJson` (`compact()` for DB, `pretty()` for files, `convert(value, type)` to re-type a cached/loaded object): records, enums, `List`/`Map`, `java.time` (`Instant`, `LocalDate`, `LocalDateTime`, `ZonedDateTime`, `Duration` as ISO strings), `LONG_OR_DOUBLE` number policy, no HTML escaping. Untyped reads return `Long`/`Double` from the backend but the *original* object while it sits in the `AbstractDataStorage` cache — consumers must use `instanceof Number`. Generic lists need a `TypeToken` (the API only takes `Class<T>`, so `List<Track>` comes back as `List<Map>`).

### Implementations (`core/storage`)
- `AbstractDataStorage` — in-memory `cache` per scope (`ConcurrentHashMap`), fires `StorageGetEvent` (pre without value, post with value; cancelling the pre-event returns the listener's value) / `StorageSetEvent` (cancellable, value replaceable) / `StorageRemoveEvent`, delegates to `doGet/doSet/doExists/doRemove/doGetKeys/doGetAll/doClear`. Rules (tested in `AbstractDataStorageTest`): misses are not cached; a cached object of another type falls through to `doGet`; **`set` calls `doSet` first and caches only on success** (a rejected write is invisible); `null` values are not storable (NPE) — use `remove`.
- `file/FileDataStorage(baseDirectory, eventManager, saveDebounceMs)` — one `data.json` per scope directory (`guild:1` → `guild/1/data.json`); `loadScopeData` lazily reads the file into the shared cache map (a `ConcurrentHashMap`, JSON nulls dropped); writes are debounced per scope (`Debouncer`), `save()` flushes, `close()` stops debouncers then saves. An empty scope is written back as `{}` when its file exists (removing the last key must stick); a malformed file is renamed `data.json.corrupt` and the scope starts empty.
- `db/DatabaseDataStorage` — HikariCP pool; single table `<prefix>storage_data(scope, key_name, value_data)`; `db/SqlDialect` picks MySQL/MariaDB vs PostgreSQL from the JDBC URL prefix (upsert syntax, text column type). Package-private constructor `(DataSource, SqlDialect, prefix, events)` for tests; `close()` only closes a `HikariDataSource`.
- `StorageFactory.createStorageManager(CoreSettings.DataStorage, eventManager)` — `type` FILE|DB (validated upstream by `CoreSettings.from`), `fallback: true` degrades to FILE when the DB is unreachable at boot (otherwise `IllegalStateException` → `main` exits). `DatabaseDataStorage(CoreSettings.Database, events)` builds the Hikari pool; `BinaryStorageFactory` mirrors it with `CoreSettings.BinaryStorage`/`S3`.

## Binary storage
- Contract: `api/storage/binary/BinaryStorage` (+ `BinaryStorageKey`, events `FileUploadEvent`/`FileDownloadEvent`/`FileDeleteEvent`). `BinaryStorageManager` is an interface (impl `core/storage/binary/SimpleBinaryStorageManager`) returning `ScopedBinaryStorage` views; `PluginBinaryStorageAdapter` namespaces paths under `<pluginId>/` and `listFiles` strips it. Paths are normalised (`\` → `/`, leading `/` dropped) by `BinaryStorageKey.normalizePath` (package-private, shared with the view).
- `core/storage/binary/AbstractBinaryStorage` (events `FileUploadEvent`/`FileDownloadEvent`/`FileDeleteEvent` may cancel; `saveFile(File)` fires the file event then the stream event), `file/FileBinaryStorage` (folder from `data.binary.storage.file.folder`; layout `<scope with ':'→'/'>/<path>`, e.g. `user/1/avatars/a.png`; legacy `user:1` folders are renamed once at startup), `s3/S3BinaryStorage` (AWS SDK v2: bucket/region/keys/endpoint/prefix/`path_style_access`; object key = `<rootPrefix/><scope>/<path>`; `getOutputStream` buffers and uploads on `close()` — S3 needs the content length — and a failed upload surfaces as `IOException`/`saveFile == false`; package-private `(name, S3Client, S3Presigner, bucket, prefix, events)` constructor for tests). `BinaryStorageFactory` mirrors `StorageFactory` incl. `fallback`.
- The shaded jar keeps JDBC drivers SPI-registered via `ServicesResourceTransformer` in `fluxcord-core/pom.xml` — keep it when touching shading.

## Gotchas
- `saveAll()` is called by `PluginManager.reloadPlugins()` before disabling; the shutdown path relies on `dataStorageManager.close()` in `FluxcordRuntime.stop()`, which stops the debouncers before the final save. A JVM *kill* (not a clean shutdown) between debounce ticks still loses the last writes (FILE backend).
- Scope strings are built by string concatenation in several places (`StorageKey` factories, adapters, `SimplePermissionManager`); grep before renaming a scope format — stored data would become unreachable.
- Changing backend FILE → DB does **not** migrate existing data; there is no migration tool.

## Testing
- Existing: `AbstractDataStorageTest` (fake backend, 13), `FileDataStorageTest` (9), `FileDataStorageColdStartTest`, `SqlDialectTest`, `ScopedStorageTest` (scope formats, plugin prefixing, binary paths — the on-disk layout contract), `StorageJsonTest`, `DatabaseDataStorageTest` (H2 `MODE=MySQL`, in-memory, a second instance on the same URL gives cold reads). `FileBinaryStorageTest` (6), `S3BinaryStorageTest` (13, mocked SDK). Missing: fallback path of the factories.
- H2 does **not** emulate PostgreSQL `INSERT ... ON CONFLICT DO UPDATE` (syntax error in `MODE=PostgreSQL`), so the PostgreSQL upsert is only pinned as text; verify against a real server via the dev bot when touching `SqlDialect`.
- For file storage tests use `@TempDir` and a tiny `saveDebounceMs`.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit.
- 2026-09-20: `FileDataStorage` writes are debounced via `core/util/Debouncer` (one single-thread scheduler per scope, daemon threads). `close()` must stop the debouncers (cancel pending + await in-flight, `Debouncer.cancelAndAwait`) **before** the synchronous `save()`, otherwise two `FileWriter`s truncate the same `data.json` concurrently and the next reader sees an empty scope. Was the cause of `FileDataStorageColdStartTest` failing only in the full suite; fixed 2026-09-20.
- 2026-09-20 (later): the first `cancelAndAwait` was wrong — `FutureTask.cancel(false)` returns true and `isCancelled()` while the task is *running* (state stays NEW during run), so it never waited. The Debouncer now tracks `running` + a generation counter under its own monitor; `DebouncerTest` covers pending-cancel, in-flight wait and daemon threads.
- 2026-09-20: `AbstractDataStorage.cache` is shared with `FileDataStorage.loadScopeData` (same map instance per scope): the cache *is* the on-disk data model for the FILE backend, so mutating it mutates what gets saved.
- 2026-09-20: **Data-loss bug fixed**: `AbstractDataStorage.set` created the scope's cache entry *before* `doSet`, so on a cold scope `FileDataStorage.loadScopeData` saw a non-null (empty) cache and never read the file — the next save wrote only the new key (e.g. saving `music.state` after a restart wiped `commands.prefix` of that guild). Also: removing the last key was never persisted (`saveScopeData` skipped empty maps), and a corrupt `data.json` threw `JsonSyntaxException` on every access (only `IOException` was caught).

- 2026-09-20 (S1/S2): managers are interfaces; the 16 scoped-view classes were deleted in favour of `ScopedStorage`/`ScopedBinaryStorage` (`namespaced(id)` composes prefixes: `a.b.key`). Plugins that referenced `PluginGuildStorage` & co must switch to `ScopedStorage` (music plugin done). Tests for the storage layer live in `fluxcord-core` — `fluxcord-api` has no test module.

- 2026-09-20 (S3/S4): the number model changed from Gson's default (`Double` for every JSON number on untyped reads) to `LONG_OR_DOUBLE`; typed reads were never affected. `PlaybackState.fromMap` already used `instanceof Number`.

- 2026-09-20: `FileDataStorage.doClear` now shuts down the scope's `Debouncer` first — a pending/in-flight debounced write raced the delete (`delete()` false on Windows while the writer holds the file, or the scope file reappearing after `clear`). Showed up as a flaky `FileDataStorageTest.clearDeletesTheScopeOnDiskAndInMemory`.

- 2026-09-20 (coverage pass): S3 uploads had never worked (`RequestBody.fromInputStream(is, -1)` rejects the unknown length; the error was logged on a background thread and `saveFile` still returned true). Found only because the class was 0 % covered.

## Known issues / open questions
