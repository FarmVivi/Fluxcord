# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Fluxcord is a modular Discord bot engine (Java 25, Maven multi-module, JDA 6). `develop` is the v3 modular refactor and the active branch; `main` is the legacy v2 monolith. Target `develop` for PRs unless told otherwise.

## Build & test

Requires JDK 25 and Maven. The project's `defaultGoal` is `clean package`.

```bash
mvn clean package                           # full build, all modules, runs tests
mvn -T1C clean package -DskipTests          # fast packaging (what CI release uses)
mvn -pl fluxcord-core -am package -DskipTests   # only core (+ api)
mvn test -pl fluxcord-core                  # tests for one module
mvn test -pl fluxcord-core -Dtest=AudioMixerTest            # single test class
mvn test -pl fluxcord-core -Dtest='SqlDialectTest#someMethod'  # single test method
mvn -B verify -Psonar -DskipTests           # SonarCloud analysis (skipped by default)
```

Tests are JUnit 5 + Mockito, and live almost entirely in `fluxcord-core/src/test`.

### Running locally

- Runnable artifact: `fluxcord-core/target/fluxcord-core-*-shaded.jar` (`java -jar ...`). `build.sh` copies it to `target/fluxcord.jar`; `./build.sh run` builds and starts it.
- Dev run: `mvn -pl fluxcord-core exec:java` uses working directory `fluxcord-core/run/` (git-ignored) and `logback-dev.xml`.
- The bot reads `config.yml`, `plugins/`, `lang/` relative to the working directory. Copy `fluxcord-core/src/main/resources/config.yml` there and set `discord.token`.
- Docker: `docker compose up --build`, or the `Dockerfile.*` variants described in README. `entrypoint.sh` installs/updates bundled plugin jars into `/app/plugins` based on `INSTALL_PLUGINS`, `INSTALL_EXAMPLES`, `AUTO_UPDATE_PLUGINS` env vars.

## Module layout

| Module | Role |
|---|---|
| `fluxcord-api` | Public contracts only (interfaces, `AbstractPlugin`, adapters). Plugins compile against this. No implementation. |
| `fluxcord-core` | The engine: `fr.farmvivi.fluxcord.core.Fluxcord` main class, all `Simple*Impl` implementations, shaded fat jar. |
| `plugin-template` | Copy-me starter for new plugins (`com.example.plugin`). |
| `examples/plugins/*` | Reference plugins (audio, commands). |
| `plugins/music-plugin`, `plugins/ai-audio-plugin` | First-party plugins built in this reactor. |

Versions of all dependencies are pinned in the root `pom.xml` `<dependencyManagement>`; child poms declare artifacts without versions. Root `<version>` (`3.0.0-SNAPSHOT`) is bumped by the `bump-*-version` GitHub workflows via `versions:set`, so don't hand-edit versions across modules.

## Architecture

### Boot sequence (`Fluxcord.main`)

`Fluxcord` is a static-singleton bootstrapper. Order matters:

1. Load `config.yml` (`CoreConfiguration`), resolve token/locale/prefix.
2. Build services: `SimpleLanguageManager` → `SimpleEventManager` + `JDADiscordAPI` → storage managers (`StorageFactory`, `BinaryStorageFactory`) → `SimplePermissionManager`, `AudioServiceImpl` → `SimpleCommandService`, `ConsoleCommandService` → `PluginManager` (receives every service).
3. `pluginManager.loadPlugins()` + `preEnablePlugins()` **before** JDA connects — plugins can still mutate `DiscordAPI.getBuilder()` (intents, listeners) at this point.
4. `discordAPI.connect().join()`, then `commandService.setJDA(...)`.
5. `enablePlugins()` → `commandService.enable()` (slash-command sync) → `postEnablePlugins()`.
6. Health server (`core/health/HealthServer`, port 8081 by default) flips to ready.

Shutdown is the reverse via a JVM shutdown hook.

### Plugin system (`core/plugin`)

- Plugins are jars in `plugins/` containing a `plugin.yml` (`id`, `name`, `version`, `main`, `dependencies`, `soft-dependencies`). `PluginDescriptor` parses it; `DependencyResolver` topologically sorts by hard/soft deps.
- Each plugin gets its own `PluginClassLoader` (child-first for classes and resources, **except** `CORE_PACKAGES` — `fr.farmvivi.fluxcord.core`, `org.slf4j`, `net.dv8tion.jda.api`, snakeyaml, gson, HikariCP, AWS SDK — which are always parent-loaded). Consequence: plugin poms mark `fluxcord-api`, `JDA`, `slf4j-api` as `provided`, and shade+relocate everything else they bundle (see `plugins/music-plugin/pom.xml` for the lavaplayer relocation pattern).
- Lifecycle: `DISCOVERED → LOADED → PRE_ENABLING → ENABLING → POST_ENABLING → ENABLED → ... → DISABLED`. `Plugin` exposes `onLoad(PluginContext)`, `onPreEnable`, `onEnable`, `onPostEnable`, `onPreDisable`, `onDisable`, `onPostDisable`. Register permissions in `onPreEnable`, commands/listeners in `onEnable`, cross-plugin integration in `onPostEnable`.
- `AbstractPlugin` is what real plugins extend; it exposes per-plugin adapters (`getPluginCommandAdapter()`, `getPluginDataStorage()`, `getPluginLanguageManager()`, `getPluginPermissionManager()`, `getPluginBinaryStorage()`) that namespace the shared services by plugin id.
- Plugin data lives in `plugins/<id>/` (config.yml, lang/, data). `PluginConfiguration` copies the default `config.yml` from the jar on first run and supports plugin-driven migration via `config_version` + `ConfigurableMigrationPlugin` / `Plugin.getMigrationClass()`.
- Resources under `src/main/resources` are Maven-filtered (`${project.version}` etc. in `plugin.yml`, `project.properties`).

### Events — two separate buses

`SimpleEventManager` (`@EventHandler` methods) only dispatches subclasses of `fr.farmvivi.fluxcord.api.event.Event` (plugin/storage/permission/language/audio/command events). It **ignores raw JDA event types** with a warning. For Discord events, plugins register a JDA `ListenerAdapter` directly: `getContext().getDiscordAPI().getBuilder().addEventListeners(...)` pre-connect, and also `getJDA().addEventListener(...)` if JDA is already up (hot-reload case) — see `MusicPlugin.onEnable`. Some docs/template snippets show `@EventHandler` on `MessageReceivedEvent`; that path does not work.

### Commands (`api/command`, `core/command`)

One `Command` model serves slash, text-prefix and console invocations. `SimpleCommandService` owns a `SimpleCommandRegistry`, parsers (`SlashCommandParser`, `TextCommandParser`, `ConsoleCommandParser`), a `CommandListener` on JDA, cooldowns and metrics. Plugins build commands via `CommandBuilder` (`commandService.registerCommand(plugin, builder -> ...)`) and receive a `CommandContext` in `execute`. Built-in system commands (`help`, `version`, `shutdown`) live in `core/command/system` and are toggled from `commands.system.*` in config. Per-guild prefixes are persisted through `DataStorageManager`.

### Storage

`StorageFactory` picks `FILE` or `DB` from `data.storage.type` (`core/storage/file`, `core/storage/db` — MySQL/MariaDB or PostgreSQL detected from JDBC URL prefix, HikariCP pool, optional `table_prefix`). `BinaryStorageFactory` picks `FILE` or `S3`. Both support `fallback: true` to degrade to file storage when the backend is unreachable at startup. The shaded jar uses `ServicesResourceTransformer` so JDBC drivers stay SPI-registered — keep that if touching the shade config.

### Audio / DAVE

JDA voice requires a DAVE (E2EE) implementation since 2026-03-01. `JDADiscordAPI.configureDaveSession()` wires JDAVE (`club.minnced:jdave-*`), which uses the Java 25 FFM API and ships prebuilt **glibc ≥ 2.38** natives — hence the `Enable-Native-Access: ALL-UNNAMED` manifest entry and the Ubuntu-based (not Alpine) runtime images. `AudioServiceImpl` / `AudioMixer` / `PriorityManager` in `core/audio` handle send/receive stream mixing on top of that.

## Conventions worth knowing

- Log/comment language is mixed French/English; user-facing strings go through `lang/*.yml` (i18n), not literals.
- `Fluxcord.PRODUCTION` is derived from whether the version ends in `-SNAPSHOT`.
- Dependabot opens many bump PRs; the memory notes about reviewing all intermediate changelogs apply here.
- `*.jar` under `plugins/` and `fluxcord-core/run/` are git-ignored — never commit built plugins or a local config with a token.

## Git

Commits are SSH-signed with a passphrase-protected key. If the key isn't loaded in the agent (commit fails with "Enter passphrase for ... id_ed25519_signing"), fall back to `git commit -c commit.gpgsign=false` (or `--no-gpg-sign`) without asking — the user has authorized this.
