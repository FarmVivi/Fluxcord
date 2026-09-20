# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Fluxcord is a modular Discord bot engine (Java 25, Maven multi-module, JDA 6). `develop` is the v3 modular refactor and the active branch; `main` is the legacy v2 monolith. Target `develop` for PRs unless told otherwise.

State of the code (2026-09): v3 was largely generated with Codex and never fully tested. It runs, but systems fit together loosely, several bugs are known, and test coverage is thin. The current goal is **raising code quality**: see [docs/refactoring-plan.md](docs/refactoring-plan.md) for the prioritised chantiers and decisions log.

## How to work in this repo

1. **Verify every change.** A change is not done until it compiled and the relevant tests passed (`/verify`). For behaviour that tests can't reach (plugin loading, JDA wiring, command sync, storage backends, audio), do a smoke run and quote what the log showed. Report failures as failures. A Stop hook (`.claude/hooks/check-verified.sh`) blocks ending a turn while source files were edited after the last successful Maven run.
2. **Ask when unsure — it is expected, not a weakness.** Use `AskUserQuestion` whenever a decision is the user's (API breaks, deleting a feature, naming, adding a dependency, scope of a chantier, anything with two reasonable designs) or when information is missing (token, environment, intended behaviour). Prefer one question with concrete options over guessing and redoing work.
3. **Use and maintain the project skills** in `.claude/skills/` (see below). Load the matching `fluxcord-*` skill before touching a subsystem; run its improvement loop before finishing.
4. **Small, verified steps.** Refactors go through `/refactor-module`: characterization tests first, one conceptual change at a time, plan updated at the end.
5. Don't fix unrelated things silently: list them in `docs/refactoring-plan.md` or tell the user.
6. **This repo is public.** The maintainer's private deployment/testing notes live in `CLAUDE.local.md` (git-excluded) and in a personal skill outside the repo; never copy hosting details, hostnames, tokens or kubeconfigs into committed files, commit messages or PR descriptions. Real-environment testing follows those private notes.

## Skills, hooks and agents (`.claude/`)

Skills are this project's "experts". Each `SKILL.md` is the owner of one system or workflow and is expected to be **wrong sometimes and corrected often**.

| Skill | Kind | Use it when |
| --- | --- | --- |
| `/verify` | task | after any code change; picks the right Maven command, optional `--smoke` |
| `/refactor-module <item>` | task | running one chantier from the refactoring plan |
| `/skill-maintenance [--audit]` | meta | end of every task that used a skill; creating or cleaning skills |
| `fluxcord-plugin-system` | reference | PluginManager, classloader, lifecycle, reload |
| `fluxcord-plugin-dev` | reference | writing/modifying a plugin, poms, adapters |
| `fluxcord-commands` | reference | command API, parsers, slash sync, cooldowns |
| `fluxcord-events` | reference | internal bus vs JDA listeners |
| `fluxcord-storage` | reference | data/binary storage, backends, fallback |
| `fluxcord-i18n` | reference | lang files, namespaces, lookup cascade |
| `fluxcord-audio` | reference | AudioService/pipeline, DAVE, music plugin |
| `fluxcord-boot` | reference | Fluxcord.main, config, JDA connection, permissions, health, Docker |

Reference skills auto-load through their `paths:` when you edit matching files; you can also invoke them explicitly. **The improvement loop** (in every skill, protocol in `/skill-maintenance`): after using a skill, (1) fix any statement the code contradicted, (2) append dated learnings a future session would need, (3) delete what is resolved or stale, (4) keep it short. Create a new skill (`.claude/skills/<name>/SKILL.md`) when a system or recurring workflow has no owner; don't duplicate CLAUDE.md in skills — CLAUDE.md is the map, skills hold the depth.

Hooks (`.claude/settings.json`, scripts in `.claude/hooks/`): `PostToolUse` marks source edits and successful Maven runs in `.claude/state/` (git-ignored); `Stop` blocks the turn if edits are unverified. Docs-only turns: `touch .claude/state/last-build`.

Subagents: use the built-in `Explore` agent for wide read-only searches; there are no custom agents in `.claude/agents/` yet — add one only for a repeatable role (e.g. a reviewer with a fixed checklist), and document it here.

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
- Dev run: `mvn -pl fluxcord-core exec:exec` forks a JVM with working directory `fluxcord-core/run/` (git-ignored) and `logback-dev.xml`. (Not `exec:java`: it runs in-process and ignores `workingDirectory`, so the bot would start at the repo root and create `config.yml`/`logs/` there.)
- Equivalent without Maven, and the way to drive the console from a script: `cd fluxcord-core/run && java -Dlogback.configurationFile=logback-dev.xml --enable-native-access=ALL-UNNAMED -jar ../target/fluxcord-core-*-shaded.jar` — piping `shutdown` on stdin triggers the clean shutdown path (`Goodbye!` in the log).
- The bot reads `config.yml`, `plugins/`, `lang/` relative to the working directory. Copy `fluxcord-core/src/main/resources/config.yml` there and set `discord.token`. Health endpoints while running: `localhost:8081/healthz`, `/readyz`, `/version`.
- Smoke-test procedure (what to look for in the log, how to stop cleanly on Windows): see the `/verify` skill, section 3.
- Docker: `docker compose up --build`, or the `Dockerfile.*` variants described in README. `entrypoint.sh` installs/updates bundled plugin jars into `/app/plugins` based on `INSTALL_PLUGINS`, `INSTALL_EXAMPLES`, `AUTO_UPDATE_PLUGINS` env vars.

## Module layout

| Module | Role |
| --- | --- |
| `fluxcord-api` | Public contracts only (interfaces, `AbstractPlugin`, adapters). Plugins compile against this. No implementation. |
| `fluxcord-core` | The engine: `fr.farmvivi.fluxcord.core.Fluxcord` main class, all `Simple*Impl` implementations, shaded fat jar. |
| `plugin-template` | Copy-me starter for new plugins (`com.example.plugin`). |
| `examples/plugins/*` | Reference plugins (audio, commands). |
| `plugins/music-plugin`, `plugins/ai-audio-plugin` | First-party plugins built in this reactor. |

Versions of all dependencies are pinned in the root `pom.xml` `<dependencyManagement>`; child poms declare artifacts without versions. Root `<version>` (`3.0.0-SNAPSHOT`) is bumped by the `bump-*-version` GitHub workflows via `versions:set`, so don't hand-edit versions across modules.

## Architecture

### Boot sequence (`Fluxcord.main` → `FluxcordRuntime`)

`Fluxcord.main` is a thin entry point: it loads `config.yml` (`CoreConfiguration`), checks the token, builds a `FluxcordRuntime(baseDir, config, new JDADiscordAPI(token))`, starts the health server, calls `runtime.start()`, then parks on `awaitShutdownRequest()` and runs `runtime.stop()` from the main thread. `System.exit` only happens in `main`; `FluxcordRuntime` throws. `FluxcordRuntimeTest` boots the whole engine on a temp dir with a mocked `DiscordAPI`.

`FluxcordRuntime` constructor wires every service from the config: `SimpleEventManager` → `SimpleLanguageManager` (+ `lang/`) → storage managers (`StorageFactory`, `BinaryStorageFactory`) → `SimplePermissionManager`, `AudioServiceImpl` → `SimpleCommandService` (+ shutdown handler), `ConsoleCommandService` → `PluginManager` (receives every service). `start()` order matters:

1. `pluginManager.loadPlugins()` + `preEnablePlugins()` **before** JDA connects — plugins can still mutate `DiscordAPI.getBuilder()` (intents, listeners) at this point.
2. `discordAPI.connect().join()`, then `commandService.setJDA(...)`, console `setJDA`, guild-operator resolver.
3. `enablePlugins()` → `commandService.enable()` (slash-command sync) → `postEnablePlugins()` → console start → default presence.
4. Health server (`core/health/HealthServer`, port 8081 by default, `HEALTH_PORT`) flips to ready.

`stop()` is the reverse (plugins, commands, console, Discord, events, storages, health), runs at most once, and is also what the JVM shutdown hook calls.

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
