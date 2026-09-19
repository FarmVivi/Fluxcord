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

## `Fluxcord.main` (core/Fluxcord.java)
Static-singleton bootstrapper: all services are `private static` fields with public static getters (`Fluxcord.getPluginManager()` etc.) — nothing in core/plugins actually calls those getters (grep `Fluxcord\.get` → 0 hits outside the class), services are passed explicitly. Order:
1. static init reads `project.properties` (Maven-filtered) → `NAME`, `VERSION`, `PRODUCTION = !VERSION.contains("-SNAPSHOT")`.
2. `initializeComponents()`: `plugins/` folder → `CoreConfiguration("config.yml")` → token check (`YOUR_BOT_TOKEN` placeholder → `System.exit(1)`) → default locale (`language.default`) → prefix (`commands.default-prefix`) → `SimpleLanguageManager` + `LanguageFileLoader("lang")` → `SimpleEventManager` + `JDADiscordAPI(token)` → `StorageFactory` / `BinaryStorageFactory` → `SimplePermissionManager(eventManager, dataStorageManager)` → `AudioServiceImpl(eventManager)` → `SimpleCommandService(...)` → `ConsoleCommandService(commandService)` → `PluginManager(all services)`.
3. shutdown hook registered; `HealthServer` started on `HEALTH_PORT` (default 8081): `/healthz`, `/readyz` (503 until ready), `/version`.
4. `startBot()`: `loadPlugins` → `preEnablePlugins` → `discordAPI.connect().join()` → `setStartupPresence` → `commandService.setJDA` + `consoleCommandService.setJDA` → `enablePlugins` → `commandService.enable()` → `postEnablePlugins` → `consoleCommandService.start()` → `setDefaultPresence` → `healthServer.setReady(true)`.
5. main thread parks on a `CountDownLatch` forever; shutdown = `pluginManager.close()` → `commandService.disable()` → console stop → `discordAPI.disconnect().join()` → `eventManager.shutdown()` → storages `close()` → health stop.

Any failure in `initializeComponents`/connect calls `System.exit(1)` (which runs the shutdown hook with half-initialised services — every `shutdownBot` step null-checks for that reason).

## Configuration (`core/config`)
`YamlConfiguration` (snakeyaml, dotted keys `getString/getInt/getBoolean/getStringList`, `set`, `save`, `reload`) ← `CoreConfiguration` adds `config_version` + `validateConfiguration()` + migration (legacy files without version are treated as 0). Keys: see `fluxcord-core/src/main/resources/config.yml` (`discord.token`, `language.default`, `commands.*`, `data.storage.*`, `data.binary.storage.*`, presence settings). Plugin configs reuse `YamlConfiguration` via `PluginConfiguration` (see `fluxcord-plugin-system`). Tests: `CoreConfigurationTest`.

## Discord (`core/discord/JDADiscordAPI`)
Builds `JDABuilder.createDefault(token)` with intents `GUILD_MEMBERS, GUILD_PRESENCES, GUILD_MESSAGES, GUILD_MESSAGE_REACTIONS, GUILD_VOICE_STATES, MESSAGE_CONTENT` (three privileged ones — must be enabled in the Discord developer portal or connect fails) and cache flags; `configureDaveSession()` (see `fluxcord-audio`). Exposes `getBuilder()` (mutable until `connect()`), `getJDA()`, `connect()/disconnect()` futures, and three presences (startup/default/shutdown) with activity + status. `reloadPlugins()` in `PluginManager` calls `disconnect()` then `connect()` again — the builder must still be usable after a first connect.

## Permissions (`core/permissions/SimplePermissionManager`, api `permissions/*`)
- `Permission(name, description, PermissionDefault)`; plugins register in `onPreEnable` via `getPluginPermissionManager().registerPermission(...)` (name convention `<pluginId>.<node>`, see `MusicPlugin.permissionKey`).
- `hasPermission(userId, guildId, name)`: explicit override from storage (user-guild then user scope, cached), else the permission's default: `TRUE`/`FALSE`, `OP` → `isOperator(userId, guildId)`, `NOT_OP` → inverse.
- **`isOperator` reads a boolean `isOperator` from user(-guild) storage and nothing in the repo ever writes it** (no command, no config list, no Discord-role/ADMINISTRATOR mapping). So every `OP`-default permission (`music.volume`, `music.admin`) is denied to everyone except the console. Plan item B1.
- Unregistered permission name → check `permissions.error.not_found` handling in `hasPermission` before relying on it.
- Fires `PermissionCheckEvent` / `PermissionChangeEvent`; `unregisterPermissions(plugin)` on disable.

## Containers
`Dockerfile` (multi-stage, `mvn -T1C -DskipTests package`, Ubuntu-based runtime for glibc ≥ 2.38), `Dockerfile.buildkit*`, `Dockerfile.optimized`; `entrypoint.sh` installs bundled plugin jars into `/app/plugins` according to `INSTALL_PLUGINS`, `INSTALL_EXAMPLES`, `AUTO_UPDATE_PLUGINS`. `docker-compose.yml` for local runs. Health probes should hit `/readyz`.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit.
- 2026-09-20: Tests added: `YamlConfigurationTest`, `EnvAwareYamlConfigurationTest` (protected `lookupEnv`/`lookupEnvValues` seams replace the environment), `HealthServerTest` (`HealthServer(0)` + `getPort()` for an ephemeral port). snakeyaml resolves YAML 1.1 booleans (`yes`/`on`) itself; `getString` on a section returns `Map.toString()` instead of failing.

## Known issues / open questions
- B1: operators are unreachable (see Permissions). Options to decide with the user: `permissions.operators: [userIds]` in config, map `OP` to Discord `ADMINISTRATOR`/guild owner, or an `op` console command. Probably all three.
- Hygiene: `YamlConfiguration.save()` (snakeyaml dump) drops every comment of `config.yml`; it runs on core migration and on `SimpleCommandService.setPrefix` (global prefix). Characterized by `YamlConfigurationTest.saveDropsComments`. Decision 2026-09-20: fix later (probably stop writing config.yml from code).
- B2: `Fluxcord` static fields + getters are dead API; replace with an instance `FluxcordRuntime` (constructor-wired services, `start()/stop()`), which also makes boot testable.
- Presence config keys and intent list are hardcoded in `JDADiscordAPI`; plugins needing extra intents must add them in `onPreEnable`.
- `System.exit(1)` inside helper methods makes unit testing impossible; throw and let `main` exit.
