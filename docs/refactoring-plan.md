# Refactoring plan (v3 / `develop`)

Living document. Owned by the `/refactor-module` skill: pick an item, do it with characterization tests, tick it, add what you discovered. Items are ordered by (risk removed × how many other items they unblock). IDs are referenced from `.claude/skills/*`.

Audit date: 2026-09-19. State of the code base then: ~28 k lines of Java in the reactor, 7 test classes (~1.1 k lines) all in `fluxcord-core`, no CI job running tests, no `@Deprecated` API, 20 broad `catch (Exception)` in `PluginManager` alone.

## 0. Safety net first

- [x] **T0 — CI runs the tests.** (2026-09-20) `.github/workflows/ci.yml`: `mvn -B -ntp verify` on push/PR to `develop`/`main` (Dependabot PRs included), Surefire reports uploaded on failure, JaCoCo 0.8.15 report-only in the root pom (core coverage at start: 9 %). First run surfaced a real race in `FileDataStorage.close()` vs the debounced background save (data loss at shutdown), fixed in the same batch.
- [x] **T1 — Test fixtures for the engine.** Done 2026-09-20 (`core/testing/PluginJars` + `FixturePlugin` + `PluginCalls`, `PluginManagerTest` 11 tests; fixed reload id/name key and class-loader leak on failed load). A `FakePlugin` (in-memory `Plugin` + `PluginDescriptor`) and a helper that builds a real plugin jar in a `@TempDir` (plugin.yml + compiled class) so `PluginManager` can be tested end-to-end. Unblocks P1/P2/E2.
- [x] **T2 — Characterization tests for the pure classes.** Done 2026-09-20 (low level): `Debouncer`, `YamlConfiguration`, `EnvAwareYamlConfiguration`, `PluginDescriptor`, `DependencyResolver`, `PluginClassLoader`, `HealthServer` (41 → 91 tests). Found and fixed: resolver returned the load order **reversed** (dependants before dependencies) and non-deterministic; `Debouncer.cancelAndAwait` did not actually wait for a running action (`FutureTask.cancel` reports success while running). `SimpleEventManager` (22), `SimpleCommandRegistry` (11, found: alias removal stole other commands' aliases), `SimpleLanguageManager` (16), `AbstractDataStorage`+`FileDataStorage` (22, found: cold-scope `set` wiped the rest of the scope on the next save, last-key removal never persisted, corrupt file crashed every access), `SimplePermissionManager` (12, found: cached defaults survived re-registration), `AudioPipeline` (13, found: fade one frame late) — all done 2026-09-20, 187 core tests. **T2 complete.**

## 1. Plugin lifecycle & identity (highest impact)

- [x] **P1 — One lifecycle path.** Done 2026-09-20: the phased boot/shutdown methods and `enablePlugin`/`disablePlugin` share `markEnabled`, `markDisabled` and `releaseResources` (events, permissions, audio, commands); the phased shutdown used to leak everything but event listeners. `cleanupResources` still releases plugins stuck in ERROR. Both paths remain (boot needs the pre-connect/post-connect split) but no longer diverge.
- [x] **P2 — Identity = `id` everywhere.** Done 2026-09-20: `PluginManager.reloadPlugin`, `AudioServiceImpl.pluginGuilds` and `AudioPipeline` handler maps are keyed by `getId()`; remaining `getName()` uses are display only. Open: should ids be forced lowercase (only the language namespace lowercases)? Convention so far: lowercase ids in plugin.yml.
- [x] **P3 — Classloader hardening.** (2026-09-20) `fr.farmvivi.fluxcord.api` added to `CORE_PACKAGES`; `getResourceAsStream` no longer uses the JarURLConnection cache (kept the jar locked on Windows after `close()`, blocking reload); template scope fixed. Left: fail fast in `loadPlugin` when a jar bundles api classes (now harmless: parent-first), and closing the classloader on every failure path (P1).
- [ ] **P4 — Dependants of failed plugins.** `DependencyResolver` only handles *missing* deps; a plugin whose hard dependency failed to load/enable is still enabled. Propagate failure along hard deps.
- [ ] **P5 — `PluginContext` exposes plugin-scoped adapters** (`getCommands()`, `getPermissions()`, `getLanguage()`, `getStorage()`) instead of only the shared managers, so namespacing can't be bypassed and `AbstractPlugin` stops building adapters itself. API addition; keep the old getters.

## 2. Boot & permissions

- [x] **B1 — Operators.** Done 2026-09-20: `permissions.operators` in config.yml, guild owner/ADMINISTRATOR mapping via a resolver installed after connect, `perm set|unset|list|nodes` command for stored per-user overrides (`/op` dropped on 2026-09-20: operators are file + Discord roles only); `shutdown` gated on operator status. Defaults evaluated live, only stored overrides cached.
- [x] **B2 — `Fluxcord` bootstrapper → instance.** Done 2026-09-20: `core/FluxcordRuntime(baseDir, config, discordAPI)` wires the services, `start()`/`stop()` (once, never throws)/`requestShutdown()`/`awaitShutdownRequest()`/`startHealthServer(port)`; `Fluxcord.main` is ~60 lines and the only place with `System.exit`; the static getters are gone. `ShutdownCommand` gets a `Runnable` (`SimpleCommandService.setShutdownHandler`). `FluxcordRuntimeTest` (7) boots the engine with a fixture plugin and a mocked `DiscordAPI`, including a failed connect and a restart reading back stored data.
- [ ] **B3 — Configuration typing.** `CoreConfiguration` is read with string keys all over (`Fluxcord`, `StorageFactory`, `SimpleCommandService`...). Introduce typed config records (`DiscordConfig`, `StorageConfig`, `CommandsConfig`) parsed once, with validation in one place.

## 3. Commands

- [x] **C1 — Split `SimpleCommandService`.** Done 2026-09-20 (917 → ~600 lines): `CommandExecutor` (gating pipeline, cooldowns, metrics, events; `CommandExecutionTest`) and `SlashCommandDataMapper` (command model → JDA `CommandData`; `SlashCommandDataMapperTest`). Sync/debounce and prefix storage stay in the service (small, and part of the `CommandService` contract). Bug fixed on the way: subcommand options lost their choices/bounds/autocomplete on sync.
- [x] **C2 — Parser loop semantics.** Done 2026-09-20: the first parser that recognises the event owns it (`dispatch`). Unknown command → debug log only (a `!foo` message that is not ours must stay silent); parse failure → ephemeral `commands.messages.parse_error` reply on the invoking transport; refused or failed execution → error reply on every transport (before, a slash command refused by permission/cooldown/guild-only got **no reply at all** — "The application did not respond").
- [x] **C3 — Autocomplete.** Done 2026-09-20: `CommandListener.onCommandAutoCompleteInteraction` → `SimpleCommandService.handleAutocomplete` (≤ 25 choices, failures answer empty); API `AutocompleteProvider<T>` + `AutocompleteContext(partial, guildId, userId, options)`, legacy `Function<String, …>` adapted; `/perm` uses it for permission nodes and a real `userOption`; console parses USER options by ID. `CommandAutocompleteTest` (5).
- [x] **C4 — Command namespace collisions.** Decided 2026-09-20: names stay global (a Discord slash name is unique per bot); a second registration — or a name that is already another command's alias — is refused with an ERROR log naming both owners, and `registerCommand` returns false. No automatic prefixing.
- [ ] **C5 — `CommandMessageBuilder`** (~610 lines) handles three transports with flags (`differ`, `ephemeral`); consider one `ReplyTarget` strategy per transport.
- [x] **C6 — Subcommands are routed.** Done 2026-09-20: `CommandParser.selectSubcommand` (name or alias) used by the three parsers (slash: `getSubcommandName()`; text/console: next token), `dispatch` executes `context.getCommand()`; `CommandExecutor` inherits permission/guild-only from ancestors and keys cooldowns by `getFullName()`. `SimpleCommandBuilder` now really links subcommands to their parent (`SimpleCommand.ParentLink` — the old code built a copy and threw it away, so `getParent()` was always null). `SubcommandRoutingTest` (7).

## 4. Events

- [x] **E1 — Dispatch semantics.** Done 2026-09-20: dispatch is polymorphic (concrete class first, then supertypes, per priority); `ignoreCancelled` now has Bukkit semantics (`true` = skipped once cancelled, default `false` = always called); handler lists are copy-on-write (registration from the main thread raced with `fireEvent` from storage/async threads); priority Javadoc fixed (LOWEST → MONITOR). Open: virtual threads for `fireEventAsync`.
- [x] **E2 — JDA listener ergonomics.** Done 2026-09-20: `DiscordAPI.addEventListeners(plugin, ...)`/`removeEventListeners(plugin)` (builder + live JDA, tracked per plugin id, released by `PluginManager.releaseResources`), `AbstractPlugin.addDiscordListeners(...)`; music/example/ai-audio plugins and the template use it; docs (`plugin-development`, `core-features`, `template-quickstart`, READMEs) rewritten — `@EventHandler` is documented as Fluxcord-events-only.

## 5. Storage

- [x] **S1 — Managers as interfaces.** Done 2026-09-20: `DataStorageManager` / `BinaryStorageManager` are api interfaces, implemented by `core/storage/SimpleDataStorageManager` and `core/storage/binary/SimpleBinaryStorageManager` (created by the factories).
- [x] **S2 — Collapse the scoped views.** Done 2026-09-20: the 16 view classes are replaced by `api/storage/ScopedStorage` and `api/storage/binary/ScopedBinaryStorage` (scope + optional namespace prefix via `namespaced(id)`; `StorageKey.*Scope()` factories own the scope formats). Storage layout unchanged (`ScopedStorageTest`). API break: plugins that named `PluginGuildStorage` & co now use `ScopedStorage`.
- [x] **S3 — Shared Gson configuration.** Done 2026-09-20: `core/storage/StorageJson` (`compact()`/`pretty()`/`convert()`; `java.time` as ISO-8601, integral numbers read as `Long`, no HTML escaping) used by both backends; supported value types documented in `docs/core-features.md`; `StorageJsonTest`.
- [x] **S4 — DB tests.** Done 2026-09-20 with H2 (test scope) in MySQL mode: `DatabaseDataStorageTest` (upsert, scopes, JSON payloads, table prefix) through a new package-private `DatabaseDataStorage(DataSource, SqlDialect, prefix, events)` constructor. H2 cannot emulate PostgreSQL's `ON CONFLICT ... DO UPDATE`; the PostgreSQL upsert stays covered by `SqlDialectTest` only — a real-server check belongs to the in-cluster dev bot.

## 6. i18n

- [ ] **L1 — Collapse the lookup cascade** in `SimpleLanguageManager.getString(locale, key)` (~300 lines of repeated steps) into an ordered list of sources; fire `StringRetrievalEvent` once; add `Optional<String> find(...)` to the api so "missing" isn't signalled by returning the key.

## 7. Audio

- [x] **A1 — Key by plugin id** (done with P2, 2026-09-20).
- [x] **A2 — Extract the send strategy** (done 2026-09-20: `SendStrategy`, one-pass BE mixer, `AudioSettings` fade/ducking, frame event opt-in) (bypass vs mix, Opus rejection, priority fades) from `AudioPipeline` into a testable class; translate the French comments while there. Decided 2026-09-20: bypass now applies volume × fade in its existing LE→BE pass (no extra pass; Opus untouched).
- [ ] **A3 — `ai-audio-plugin`** is a stub of TODOs; remove from the reactor or make it a real example — user decision.

## 8. Hygiene (do opportunistically inside the chantiers above, never as drive-by commits)

- Broad `catch (Exception)` → catch what the call can throw; never swallow without logging the cause.
- Null-checks on always-injected services (`if (eventManager != null)` ×20 in `PluginManager`) → make the constructor require them.
- French/English mix in comments and logs: new text in English; rewrite only what you touch.
- ~~`sonar-project.properties` says `sonar.java.source=17` while the build targets 25.~~ Fixed 2026-09-20; the Sonar workflow now also runs the tests so coverage reaches SonarCloud.
- The `Deploy` webhook step in `.github/workflows/docker-image-ci.yml` (and the `DEPLOY_WEBHOOK_URL` secret) is a leftover of the v2 VPS setup; deployments are now handled outside this repo. Remove the step.
- `docs/` describe intended behaviour; after each chantier update the relevant page.

### Found in the field
- [~] **M1 — YouTube playback.** `youtube-source 1.18.2` (latest release, 2026-07) is broken by YouTube-side changes (issues #226 #236 #240; IOS client version, TV client, cipher). Fixed on master but unreleased (#244). 2026-09-20: pinned the per-commit snapshot `2be8e542…-SNAPSHOT` (2026-09-17) from `maven.lavalink.dev/snapshots` — playback works again through the fallback clients, but the first attempt still logs `Must find sig function` for player `4fd832e7` (noisy, not fatal). **Switch back to a release as soon as 1.18.3 exists**; consider `remote poToken` (PR #229) if "Sign in to confirm" comes back.

## Decisions log

| Date | Decision | By |
|---|---|---|
| 2026-09-19 | Plan created; skills + Stop hook introduced; first chantier to be chosen after review. | user + Claude |
| 2026-09-20 | T0 done as a dedicated workflow + JaCoCo report-only (no threshold), CI also on Dependabot PRs. Keep working on `develop`; branches optional. | user |
| 2026-09-20 | E1: polymorphic dispatch and Bukkit `ignoreCancelled` semantics (API behaviour change; no plugin in the repo used `ignoreCancelled`). | user |
| 2026-09-20 | i18n fallback: configured default locale before en-US. Audio: lone PCM source keeps the bypass path but gets volume/fade applied in the byte-swap pass. | user |
| 2026-09-20 | B1: operators = config list + guild owner/ADMINISTRATOR (no `op` command); `perm` command manages stored overrides. Ducking default 20 %. | user |
| 2026-09-20 | S1/S2: break the api cleanly (no @Deprecated shims) — `ScopedStorage`/`ScopedBinaryStorage` replace the 16 scoped views; S4 will use H2 (test scope). | user |
| 2026-09-20 | C4: command names stay global; collisions are refused loudly (error log naming both owners), no per-plugin prefixing. | user |
