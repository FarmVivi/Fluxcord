---
name: fluxcord-boot
description: Expert on Fluxcord's core boot, configuration, Discord connection and permissions - Fluxcord.main static bootstrapper and shutdown hook, CoreConfiguration/YamlConfiguration (config.yml, config_version migration), JDADiscordAPI (JDABuilder intents/cache, presences, DAVE), SimplePermissionManager (PermissionDefault TRUE/FALSE/OP/NOT_OP, storage-backed overrides), HealthServer, Docker/entrypoint. Use when changing startup order, service wiring, config keys, intents, presence, permissions/operators, health endpoints or containers.
paths:
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/Fluxcord.java
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/config/**
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/discord/**
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/permissions/**
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/health/**
  - fluxcord-api/src/main/java/fr/farmvivi/fluxcord/api/permissions/**
  - fluxcord-core/src/main/resources/config.yml
  - Dockerfile*
  - entrypoint.sh
---

# Boot, config, Discord connection, permissions

## `Fluxcord.main` → `FluxcordRuntime` (core/Fluxcord.java, core/FluxcordRuntime.java)
- `Fluxcord` keeps only the statics read elsewhere: `NAME`, `VERSION`, `PRODUCTION = !VERSION.contains("-SNAPSHOT")` (from Maven-filtered `project.properties`; used by `VersionCommand`, `JDADiscordAPI` default activity, health `/version`). `main`: `CoreConfiguration(baseDir/config.yml)` → token check (`YOUR_BOT_TOKEN`/blank → exit 1) → `new FluxcordRuntime(baseDir, config, new JDADiscordAPI(token))` → shutdown hook (`runtime::stop`, thread `Fluxcord-Shutdown`) → `startHealthServer(HEALTH_PORT|8081)` → `start()` (failure → `stop()` + exit 1) → `awaitShutdownRequest()` → `stop()` → `System.exit(0)`. `System.exit` exists only in `main` and the static initialiser.
- `FluxcordRuntime` constructor (no I/O towards Discord): `plugins/` folder → `SimpleEventManager` → `SimpleLanguageManager(defaultLocale, events)` + `LanguageFileLoader(baseDir/lang)` → `StorageFactory` / `BinaryStorageFactory` (folders from config, **cwd-relative unless absolute** — tests set absolute paths) → `SimplePermissionManager(events, storage, permissions.operators)` → `AudioServiceImpl(events, AudioSettings.fromConfig)` → `SimpleCommandService(...)` + `setShutdownHandler(runtime::requestShutdown)` → `ConsoleCommandService` → `PluginManager(all services)`. Throws on a storage backend failure.
- `start()`: `loadPlugins` → `preEnablePlugins` → `discordAPI.connect().join()` → `setStartupPresence` → `commandService.setJDA` + console `setJDA` + `permissionManager.setGuildOperatorResolver(isGuildOperator)` → `enablePlugins` → `commandService.enable()` → `postEnablePlugins` → console `start()` → `setDefaultPresence` → health ready. Not reentrant (`IllegalStateException`); a failed connect propagates (`CompletionException`) and the caller runs `stop()`.
- `stop()`: at most once (`AtomicBoolean`), never throws, safe half-started: `pluginManager.close()` → `commandService.disable()` → console stop → `disconnect().join()` → `eventManager.shutdown()` → storages `close()` → health stop.
- `PluginConfiguration` still resolves `plugins/<id>/config.yml` from the `plugins.dir` system property / `DISCORD_PLUGINS_DIR` / cwd `plugins` — not from the runtime's `baseDir`; tests set the property (see `FluxcordRuntimeTest`, `PluginManagerTest`).

## Configuration (`core/config`)
`YamlConfiguration` (snakeyaml, dotted keys `getString/getInt/getBoolean/getStringList`, `set`, `save`, `reload`) ← `CoreConfiguration` adds `config_version` + `validateConfiguration()` + migration (legacy files without version are treated as 0). Keys: see `fluxcord-core/src/main/resources/config.yml` (`discord.token`, `language.default`, `commands.*`, `data.storage.*`, `data.binary.storage.*`, presence settings). Plugin configs reuse `YamlConfiguration` via `PluginConfiguration` (see `fluxcord-plugin-system`). Tests: `CoreConfigurationTest`.

## Discord (`core/discord/JDADiscordAPI`)
Builds `JDABuilder.createDefault(token)` with intents `GUILD_MEMBERS, GUILD_PRESENCES, GUILD_MESSAGES, GUILD_MESSAGE_REACTIONS, GUILD_VOICE_STATES, MESSAGE_CONTENT` (three privileged ones — must be enabled in the Discord developer portal or connect fails) and cache flags; `configureDaveSession()` (see `fluxcord-audio`). Exposes `getBuilder()` (mutable until `connect()`), `getJDA()`, `connect()/disconnect()` futures, and three presences (startup/default/shutdown) with activity + status. `reloadPlugins()` in `PluginManager` calls `disconnect()` then `connect()` again — the builder must still be usable after a first connect.

## Permissions (`core/permissions/SimplePermissionManager`, api `permissions/*`)
- `Permission(name, description, PermissionDefault)`; plugins register in `onPreEnable` via `getPluginPermissionManager().registerPermission(...)` (name convention `<pluginId>.<node>`, see `MusicPlugin.permissionKey`).
- `hasPermission(userId, guildId, name)`: explicit override from storage (user-guild then user scope, cached), else the permission's default: `TRUE`/`FALSE`, `OP` → `isOperator(userId, guildId)`, `NOT_OP` → inverse.
- Operators (B1, done 2026-09-20): global = `permissions.operators` in config.yml (no runtime list: `/op` was dropped 2026-09-20, operators are managed in the file + Discord roles); guild-level = owner or `ADMINISTRATOR` member, via `setGuildOperatorResolver` installed by `Fluxcord` after connect (JDA cache, no REST). `OP`/`NOT_OP` defaults are evaluated live with the guild id; only stored overrides are cached (`Optional` per key). `shutdown` is gated on `isOperator` (its old unregistered `discobocor.admin.shutdown` permission made it console-only).
- `/perm set|unset|list|nodes` (`commands.system.perm`, operators only): stored per-user overrides, scope `guild` (default on Discord) or `global`; `PermissionManager.unsetPermission` added for it.
- Unregistered permission name → `false` unless a stored override exists.
- Fires `PermissionCheckEvent` (cancel + `setResult` to force an answer without storage) / `PermissionChangeEvent` (old/new value); `unregisterPermissions(plugin)` on disable.
- Results are cached per user (and per user-guild for stored overrides) with no TTL; `setPermission`/`clearPermissions` update the cache, and since 2026-09-20 register/unregister invalidate the cached results of that permission (a reloaded plugin with another default was ignored before). External storage edits need `clearCaches()`. Tests: `SimplePermissionManagerTest` (12, in-memory `DataStorage`).

## Containers
`Dockerfile` (multi-stage, `mvn -T1C -DskipTests package`, Ubuntu-based runtime for glibc ≥ 2.38), `Dockerfile.buildkit*`, `Dockerfile.optimized`; `entrypoint.sh` installs bundled plugin jars into `/app/plugins` according to `INSTALL_PLUGINS`, `INSTALL_EXAMPLES`, `AUTO_UPDATE_PLUGINS`. `docker-compose.yml` for local runs. Health probes should hit `/readyz`.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit.
- 2026-09-20: Tests added: `YamlConfigurationTest`, `EnvAwareYamlConfigurationTest` (protected `lookupEnv`/`lookupEnvValues` seams replace the environment), `HealthServerTest` (`HealthServer(0)` + `getPort()` for an ephemeral port). snakeyaml resolves YAML 1.1 booleans (`yes`/`on`) itself; `getString` on a section returns `Map.toString()` instead of failing.
- 2026-09-20: Shutdown is driven by the **main thread**: `FluxcordRuntime.requestShutdown()` (installed as the `shutdown` command handler) releases the latch, main runs `stop()` (once) then `System.exit(0)`; the JVM hook (`Fluxcord-Shutdown`) calls `stop()` for SIGTERM/Ctrl+C. `ShutdownCommand` replies, waits 1 s, then requests. Before: `System.exit` from a daemon thread inside a slash-command executor left the JVM alive with logging dead and Discord showing "the application did not respond" (never fully explained — no thread was blocked in the dump).
- 2026-09-20: `SimplePermissionManagerTest` (14). Earlier the guild-scoped check fell back to the global default resolution (guild id lost) and default-derived results were cached forever; both gone with the B1 rewrite.

- 2026-09-20 (B2): `FluxcordRuntime` replaces the static bootstrapper; `FluxcordRuntimeTest` boots a real engine (fixture plugin jar, FILE storage in a temp dir, mocked `DiscordAPI` + `JDA` with status `LOADING_SUBSYSTEMS` so `commandService.enable()` skips the slash sync, health server on port 0). `ConsoleCommandService.start()` reads `System.in` on a daemon thread — harmless under surefire.

## Known issues / open questions
- Hygiene: `YamlConfiguration.save()` (snakeyaml dump) drops every comment of `config.yml`; it runs on core migration and on `SimpleCommandService.setPrefix` (global prefix). Characterized by `YamlConfigurationTest.saveDropsComments`. Decision 2026-09-20: fix later (probably stop writing config.yml from code).
- Presence config keys and intent list are hardcoded in `JDADiscordAPI`; plugins needing extra intents must add them in `onPreEnable`.
- `StorageFactory`/`BinaryStorageFactory` folders and `PluginConfiguration` are cwd-relative; `FluxcordRuntime.baseDir` only governs `plugins/` and `lang/`. Worth unifying with B3 (typed config).
