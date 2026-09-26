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

## Audit checklist for a generated plugin (2026-09-22)

Every plugin of this repo was first generated by Codex, and the same defects came back in each one.
Run through this list before trusting a plugin — each item was a real bug found in at least two of
`music-plugin`, `plugin-example-commands`, `plugin-example-audio`, `plugin-template`, `ai-audio-plugin`.

1. **Language keys that never resolve.** `PluginLanguageAdapter` already namespaces by plugin id, so
   a top-level wrapper in `lang/*.yml` (`music:`, `commands:`, `template:`, `aiaudio:`) shifts every
   key by one level. A miss is silent: `getString` returns the key itself, and the user reads
   `ping.response` in Discord. Check the code's keys against the flattened file, and copy
   `LanguageFilesTest` (both locales, same keys, same `{0}` placeholders).
2. **Config keys the code never reads — or reads under another name.** `plugin-example-commands`
   declared cooldowns nothing applied; `ai-audio-plugin` read `ai.transcription_language`,
   `ai.tts_voice`, `ai.enable_voice_commands`, `ai.confidence_threshold`, none of which exist in its
   own `config.yml`. Diff the keys in `config.yml` against the `getConfiguration().getX("...")`
   calls, both ways.
3. **Framework features reimplemented by hand.** Cooldowns and permissions are declarative:
   `.cooldown(seconds)` and `.permission(name)` on the builder, enforced by the core before the
   executor runs, with the message in the caller's locale. A manual check in the executor is both
   duplicated and usually wrong (`isOnCooldown` answers about the cooldown the *core* applies).
4. **Permission names not matching what was registered.** They are namespaced by plugin id
   (`<id>.node`); `ExampleCommand` asked for `template.use` while the template registered
   `plugin-template.use`, so the check could never pass. Never hardcode the prefix.
5. **Lifecycle ordering.** Anything the JDA listeners or commands depend on must exist *before* they
   are registered: `ai-audio-plugin` installed its voice listener before creating the services,
   `plugin-template` registered permissions in `onPreEnable` from flags only read in `onLoad`.
   `onDisable` must be idempotent (null the services after shutting them down).
6. **Dependency scopes.** `fluxcord-api`, `JDA` and `slf4j-api` are `provided` (the core supplies
   them); the examples had `fluxcord-api` in `compile`. Anything else bundled must be shaded *and*
   relocated, and the shade config excludes `module-info.class`, `META-INF/MANIFEST.MF` and
   `META-INF/DEPENDENCIES` while merging licences.
7. **Dead or lying code.** Commented-out snippets using an API that does not exist (`@Command`
   annotations in `ai-audio-plugin`), `// TODO` above the code that already does the thing, "persist
   the defaults" loops calling `set()` without `save()`, `Math.random()` log samplers,
   `getAsTag()` (the discriminator era — use `getName()`).
8. **Private inner classes that should be top-level** when they carry real logic: they cannot be
   tested otherwise (`WavFileSendHandler` / `WavRecordingReceiveHandler`).
9. **Test setup missing.** A plugin module needs `junit-jupiter`, `mockito-core`, `snakeyaml` (for
   the language test) and `logback-classic` in test scope, plus `src/test/resources/logback-test.xml`
   — without an SLF4J binding, JDA prints its "missing SLF4J implementation" fallback in the build.

Testing a plugin: mock `PluginContext` (it hands out every adapter), call `onLoad` then the
lifecycle hooks, and replay the captured `Consumer<CommandBuilder>` against a recording
`CommandBuilder` mock to assert names, options, cooldowns and permissions.

## Known issues / open questions
- P3: add `fr.farmvivi.fluxcord.api` to `CORE_PACKAGES`; fix template scope; consider failing fast in `loadPlugin` when a plugin jar contains `fr/farmvivi/fluxcord/api/` classes.
- No helper for "register a JDA listener bound to this plugin" — every plugin re-implements the builder/JDA dual registration and often forgets removal.

- 2026-09-26 (ai-audio-plugin): two more entries for the audit checklist above, both found in a plugin that had already been through it once.
  - **Dependencies declared, shaded, relocated and never imported.** ai-audio bundled okhttp (+okio) and a relocated Gson without a single line using either; `grep -rn "okhttp\|com.google.gson" src` returned nothing. Worse, relocating Gson is wrong whatever the usage: it is in `PluginClassLoader.CORE_PACKAGES`, so the parent copy always wins and the bundled one is dead weight. With both gone the shade plugin had nothing left to do and went too. **Check what a plugin pom bundles against what its sources import**, and prefer the JDK (`java.net.http`) over adding a client to shade.
  - **A README describing a plugin that does not exist.** ai-audio's documented 8 commands with permissions, none registered, and a configuration block whose keys had been deleted days earlier — so it was a plausible, confident, wrong specification. When auditing a generated plugin, read its README against its `registerCommand` calls and its `config.yml`; a doc that disagrees with the code is worse than no doc.
- 2026-09-26: **`ScopedStorage.get(key, Class<T>)` takes no type token**, so a collection cannot round-trip: `get("x", List.class)` hands back Gson's raw maps. Store one key per element with a non-generic record as the value (`Turn.class`) — reads stay typed, and trimming or deleting becomes key removal. `ScopedStorage.namespaced(...)` gives each collection its own key prefix, and `clear()` on a namespaced view only removes that prefix's keys.
- 2026-09-26: **every module with `lang/*.yml` needs its own `LanguageFilesTest`** — `music-plugin` was the one module without it, and it was carrying two user-visible misses. Copy `plugins/ai-audio-plugin`'s (keys are strings, locales in step, placeholders in step, no lone apostrophe in a formatted value) and add the module-specific check: in `music-plugin` the test *scans the sources* for `"music.…"` literals passed to `text(` / `getString(` instead of keeping a hand-written list, which is what caught `music.error.guild_only`. Scan translation calls only — a plugin may also hold `music.*` literals that are permission nodes.
- 2026-09-26: **audit checklist item 2 is now automated, and it runs both ways.** `ConfigurationKeysTest` (in `fluxcord-core` and in `plugin-template`, meant to be copied into every plugin) regexes the module's sources for keys handed to a *configuration* receiver — `getConfiguration().getX("…")` or a `*config*` variable, never the language manager, whose methods have the same names — and compares them with the shipped `config.yml` in both directions. A key read but not shipped is undiscoverable; a key shipped but not read looks like a setting and changes nothing. The first sweep found 3 of the former (including `data.storage.file.folder`, where the FILE backend writes everything) and **60 of the latter**: 18 in the template, 17 in the commands example, 4 in music (a vote-skip feature that does not exist, plus YouTube credentials nothing reads), 3 in the audio example, 1 in the core that its own migration injected into every config.
- 2026-09-26: the same "declared but never used" sweep applies to `lang/*.yml`, and it is where the worst was hiding: the **audio example still had the `audioexample:` wrapper** (missed when the other two were fixed on 2026-09-22) and every one of its 14 strings was unused — the plugin has no user-facing text at all, so the folder is gone. The template declared 38 strings and used 6; the commands example declared 34 and used 18; the core had a duplicate `success:` key in the same block, where the second silently won. When auditing, scan both directions for config **and** language keys.
