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
- `api/storage/DataStorageManager` is a **concrete class in the api module** wrapping one `DataStorage`; it hands out scoped views `GlobalStorage`, `UserStorage`, `GuildStorage`, `UserGuildStorage`, and `saveAll()/close()`.
- Plugin namespacing: `PluginDataStorageAdapter` (from `AbstractPlugin.getPluginDataStorage()`) returns `PluginGlobalStorage`/`PluginUserStorage`/`PluginGuildStorage`/`PluginUserGuildStorage`, which prefix keys with `<pluginId>.` — same scope, prefixed key. Core uses unprefixed keys (e.g. `commands.prefix` in guild scope for prefixes, permissions in `SimplePermissionManager`).
- Values are serialized with Gson (both backends); `get` deserializes to the requested class. Records / complex generics need care (Gson + Java records works since 2.10 but check `TypeToken` needs for lists).

### Implementations (`core/storage`)
- `AbstractDataStorage` — in-memory `cache` per scope (`ConcurrentHashMap`), fires `StorageGetEvent`/`StorageSetEvent`/`StorageRemoveEvent`, delegates to `doGet/doSet/doExists/doRemove/doGetKeys/doGetAll/doClear`. Cache is only trusted when the cached object matches the requested type (see comment about cold-start reads, covered by `FileDataStorageColdStartTest`).
- `file/FileDataStorage(baseDirectory, eventManager, saveDebounceMs)` — one `data.json` per scope directory under the base dir; writes are debounced (`Debouncer` in `core/util`), `save()` flushes, `close()` saves.
- `db/DatabaseDataStorage` — HikariCP pool; single table `<prefix>storage_data(scope, key_name, value_data)`; `db/SqlDialect` picks MySQL/MariaDB vs PostgreSQL from the JDBC URL prefix (upsert syntax, text column type). Tested by `SqlDialectTest`.
- `StorageFactory.createStorageManager(config, eventManager)` — reads `data.storage.type` (`FILE`|`DB`), validates DB settings, and if `data.storage.fallback: true` degrades to FILE when the DB is unreachable at boot (otherwise throws → `Fluxcord` exits).

## Binary storage
- Contract: `api/storage/binary/BinaryStorage` (+ `BinaryStorageKey`, scoped views `GlobalBinaryStorage`... and `Plugin*BinaryStorage` namespaced variants, events `FileUploadEvent`/`FileDownloadEvent`/`FileDeleteEvent`).
- `core/storage/binary/AbstractBinaryStorage`, `file/FileBinaryStorage` (folder from `data.binary.storage.file.folder`), `s3/S3BinaryStorage` (~510 lines, AWS SDK v2: bucket/region/keys/endpoint/prefix/`path_style_access`). `BinaryStorageFactory` mirrors `StorageFactory` incl. `fallback`.
- The shaded jar keeps JDBC drivers SPI-registered via `ServicesResourceTransformer` in `fluxcord-core/pom.xml` — keep it when touching shading.

## Gotchas
- `saveAll()` is called by `PluginManager.reloadPlugins()` before disabling; the shutdown path relies on `dataStorageManager.close()` in `Fluxcord.shutdownBot`, which now stops the debouncers before the final save. A JVM *kill* (not a clean shutdown) between debounce ticks still loses the last writes (FILE backend).
- Scope strings are built by string concatenation in several places (`StorageKey` factories, adapters, `SimplePermissionManager`); grep before renaming a scope format — stored data would become unreachable.
- `DataStorageManager` living in `fluxcord-api` as a class (with an SLF4J logger) means api consumers can't mock it as an interface and the api module carries implementation (plan item S1).
- Changing backend FILE → DB does **not** migrate existing data; there is no migration tool.

## Testing
- Existing: `FileDataStorageColdStartTest`, `SqlDialectTest`. Missing: `AbstractDataStorage` cache semantics, `DatabaseDataStorage` (could use H2 in MySQL mode, or Testcontainers — ask the user before adding a test dependency), fallback path of the factories, binary storages.
- For file storage tests use `@TempDir` and a tiny `saveDebounceMs`.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit.
- 2026-09-20: `FileDataStorage` writes are debounced via `core/util/Debouncer` (one single-thread scheduler per scope, daemon threads). `close()` must stop the debouncers (cancel pending + await in-flight, `Debouncer.cancelAndAwait`) **before** the synchronous `save()`, otherwise two `FileWriter`s truncate the same `data.json` concurrently and the next reader sees an empty scope. Was the cause of `FileDataStorageColdStartTest` failing only in the full suite; fixed 2026-09-20.
- 2026-09-20: `AbstractDataStorage.cache` is shared with `FileDataStorage.loadScopeData` (same map instance per scope): the cache *is* the on-disk data model for the FILE backend, so mutating it mutates what gets saved.

## Known issues / open questions
- S1: turn `DataStorageManager`/`BinaryStorageManager` into interfaces in api with implementations in core (API break for plugins that `new` them — unlikely; ask).
- S2: the 8 scoped-view classes × 2 (plain / plugin-prefixed) × 2 (data / binary) are near-duplicates; consider one generic `ScopedStorage` with a key-prefix strategy.
- Gson instances are static per class; a shared, configured `Gson` (records, `Instant`, enums) would avoid divergence between FILE and DB serialization.
