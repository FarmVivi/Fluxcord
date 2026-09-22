---
name: fluxcord-plugin-dev
description: How to write, build and wire a Fluxcord plugin (AbstractPlugin lifecycle hooks, plugin.yml, per-plugin adapters for commands/permissions/i18n/storage/binary, JDA listeners, config.yml + migration, Maven provided/shade/relocate rules, testing a plugin). Use when creating a new plugin, modifying music-plugin / ai-audio-plugin / examples / plugin-template, or answering "how does a plugin do X".
paths:
  - plugins/**
  - examples/**
  - plugin-template/**
  - docs/plugin-development.md
  - docs/plugins/**
---

# Writing a plugin

Engine internals: `fluxcord-plugin-system`. This skill is the plugin author's view.

## Skeleton
1. Module under `plugins/<name>` (first-party) or copy `plugin-template/` (package `com.example.plugin`). Add it to the root `pom.xml` `<modules>` if it must build in the reactor. Versions come from the root `<dependencyManagement>`; don't put versions in the plugin pom.
2. `src/main/resources/plugin.yml` (Maven-filtered): `id`, `name`, `version`, `main`, `description`, `authors`, `dependencies: []`, `soft-dependencies: []`. `id` is the identity everywhere (data folder `plugins/<id>/`, lang namespace, permission prefix, storage key prefix). Keep it lowercase, stable.
3. Main class `extends AbstractPlugin`. Hooks and what belongs where:
   - `onLoad(ctx)` — call `super.onLoad(ctx)` first (it wires `logger`, services, adapters, creates the data folder). Read config only.
   - `onPreEnable()` — register permissions (`getPermissions().registerPermission(new SimplePermission("<id>.node", desc, PermissionDefault.X))`), add JDA intents/listeners on `getContext().getDiscordAPI().getBuilder()` (JDA not connected yet at boot).
   - `onEnable()` — register commands (`commandService.registerCommand(this, b -> b.name(...)...)` or `getCommands().registerCommand(...)`), register Fluxcord event listeners (`eventManager.registerListener(obj, this)`) and Discord listeners with `addDiscordListeners(listenerAdapter...)` (handles pre/post-connect and is auto-removed on disable).
   - `onPostEnable()` — cross-plugin integration (other plugins are enabled now).
   - `onPreDisable/onDisable/onPostDisable()` — stop threads/schedulers, `jda.removeEventListener(...)`, close audio (`audioService.deregisterSendHandler`), save data. The core unregisters your commands/permissions/internal listeners only on the single-plugin disable path; don't rely on it (see engine skill).
4. Services available as protected fields after `onLoad`: `logger`, `eventManager`, `discordAPI`, `configuration` (plugin `config.yml`), `dataFolder`, `languageManager`, `dataStorageManager`, `binaryStorageManager`, `permissionManager`, `audioService`, `commandService`. Prefer the namespaced adapters: `getCommands()`, `getPermissions()`, `getLanguage()`, `getStorage()`, `getBinaryStorage()`.

## Strings, config, data
- Texts: `src/main/resources/lang/en-US.yml` (+ `fr-FR.yml`), used through `getLanguage().getString("key", args)`; runtime overrides in `plugins/<id>/lang/`. See `fluxcord-i18n`.
- Config: `src/main/resources/config.yml` is copied to `plugins/<id>/config.yml` on first run. Versioned with `config_version`; implement `ConfigurableMigrationPlugin` (or return a class from `getMigrationClass()`) to migrate.
- Data: `getStorage().getGuildStorage(guildId).set("key", value)` (Gson-serialised). Binary blobs: `getBinaryStorage()`.

## Maven rules (classloader consequences)
- `fluxcord-api`, `JDA`, `slf4j-api` → `<scope>provided</scope>`. They are loaded by the core. (`plugin-template/pom.xml` currently says `compile` for `fluxcord-api` — harmless while the template isn't shaded, but wrong; and `fr.farmvivi.fluxcord.api` is **not** in `PluginClassLoader.CORE_PACKAGES`, so a plugin jar that bundles api classes would load its own copy child-first and fail with `ClassCastException`/`LinkageError`. Plan item P3.)
- Anything else you need at runtime must be **shaded into the plugin jar and relocated** (child-first loader, no shared lib folder): pattern in `plugins/music-plugin/pom.xml` (`maven-shade-plugin`, relocations `dev.arbjerg` → `fr.farmvivi.fluxcord.shaded.dev.arbjerg`, ...). Libraries in `CORE_PACKAGES` (gson, snakeyaml, HikariCP, AWS SDK, slf4j, JDA) come from the core and must not be shaded.
- Output jar goes to `plugins/` of the runtime dir (`fluxcord-core/run/plugins/` in dev). Built jars are git-ignored.

## Testing a plugin
- Unit-test pure classes (schedulers, parsers) with JUnit 5 + Mockito (dependencies already managed). `plugin-template/src/test/.../TemplatePluginTest.java` shows the minimum.
- Lifecycle can be exercised with a mocked `PluginContext` (Mockito on the interface) — call `onLoad(ctx)` then the hooks in order.
- End-to-end: `/verify --smoke` after copying the shaded jar.

## Reference implementations
- `plugins/music-plugin` — the most complete (commands, permissions, JDA listeners, audio, i18n, buttons/modals).
- `examples/plugins/plugin-example-commands` — command API tour (options, subcommands, replies).
- `examples/plugins/plugin-example-audio` — send/receive handlers, priority ducking.
- `plugins/ai-audio-plugin` — a stub (TODOs only); don't copy patterns from it.
- Docs: `docs/plugin-development.md`, `docs/commands.md`, `docs/audio-api.md`, `docs/plugins/*` (event sections fixed 2026-09-20).

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

- 2026-09-20 (P5): `PluginContext` now also offers the plugin-scoped views directly (`getCommands()`, `getPermissions()`, `getLanguage()`, `getStorage()`, `getBinaryStorage()`); `AbstractPlugin.getPlugin*()` return the same instances. Plugins that don't extend `AbstractPlugin` can use them instead of rebuilding adapters.

## Learnings
- 2026-09-19: Initial audit.

- 2026-09-22 (examples/template tested, 0 % → ~90/74 %): three traps found by writing those tests, all worth checking in any new plugin.
  1. **Language keys**: the adapter already namespaces by plugin id, so a top-level wrapper in `lang/*.yml` (`template:`, `commands:`) makes every lookup miss — and a miss silently returns the key itself. Copy `LanguageFilesTest` (loads both locales with snakeyaml, asserts the keys the code uses exist and that the placeholders match).
  2. **Cooldowns and permissions are declarative**: `.cooldown(seconds)` / `.permission(name)` on the builder. Checking them by hand in the executor duplicates the core, and `getCommands().isOnCooldown(...)` answers about the cooldown the *core* applies — always false if nothing declared one.
  3. **Permission names are namespaced by the plugin id**; a hardcoded name that does not match what was registered can never pass.
- 2026-09-22: A plugin's own classes are far easier to test as top-level classes than as `private static` inner ones (`WavFileSendHandler` / `WavRecordingReceiveHandler` in the audio example). Plugin modules need `junit-jupiter`, `mockito-core`, and `logback-classic` in test scope — without a binding JDA prints "missing SLF4J implementation" in the build output.

## Known issues / open questions
- P3: add `fr.farmvivi.fluxcord.api` to `CORE_PACKAGES`; fix template scope; consider failing fast in `loadPlugin` when a plugin jar contains `fr/farmvivi/fluxcord/api/` classes.
- No helper for "register a JDA listener bound to this plugin" — every plugin re-implements the builder/JDA dual registration and often forgets removal.
