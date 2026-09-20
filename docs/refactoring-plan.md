# Refactoring plan (v3 / `develop`)

Living document. Owned by the `/refactor-module` skill: pick an item, do it with characterization tests, tick it, add what you discovered. Items are ordered by (risk removed × how many other items they unblock). IDs are referenced from `.claude/skills/*`.

Audit date: 2026-09-19. State of the code base then: ~28 k lines of Java in the reactor, 7 test classes (~1.1 k lines) all in `fluxcord-core`, no CI job running tests, no `@Deprecated` API, 20 broad `catch (Exception)` in `PluginManager` alone.

## 0. Safety net first

- [x] **T0 — CI runs the tests.** (2026-09-20) `.github/workflows/ci.yml`: `mvn -B -ntp verify` on push/PR to `develop`/`main` (Dependabot PRs included), Surefire reports uploaded on failure, JaCoCo 0.8.15 report-only in the root pom (core coverage at start: 9 %). First run surfaced a real race in `FileDataStorage.close()` vs the debounced background save (data loss at shutdown), fixed in the same batch.
- [ ] **T1 — Test fixtures for the engine.** A `FakePlugin` (in-memory `Plugin` + `PluginDescriptor`) and a helper that builds a real plugin jar in a `@TempDir` (plugin.yml + compiled class) so `PluginManager` can be tested end-to-end. Unblocks P1/P2/E2.
- [x] **T2 — Characterization tests for the pure classes.** Done 2026-09-20 (low level): `Debouncer`, `YamlConfiguration`, `EnvAwareYamlConfiguration`, `PluginDescriptor`, `DependencyResolver`, `PluginClassLoader`, `HealthServer` (41 → 91 tests). Found and fixed: resolver returned the load order **reversed** (dependants before dependencies) and non-deterministic; `Debouncer.cancelAndAwait` did not actually wait for a running action (`FutureTask.cancel` reports success while running). `SimpleEventManager` (22), `SimpleCommandRegistry` (11, found: alias removal stole other commands' aliases), `SimpleLanguageManager` (16), `AbstractDataStorage`+`FileDataStorage` (22, found: cold-scope `set` wiped the rest of the scope on the next save, last-key removal never persisted, corrupt file crashed every access), `SimplePermissionManager` (12, found: cached defaults survived re-registration), `AudioPipeline` (13, found: fade one frame late) — all done 2026-09-20, 187 core tests. **T2 complete.**

## 1. Plugin lifecycle & identity (highest impact)

- [ ] **P1 — One lifecycle state machine.** `PluginManager` has two parallel paths: phased `preEnablePlugins/enablePlugins/postEnablePlugins` (boot) and `enablePlugin/disablePlugin` (reload). They diverge in cleanup: the phased shutdown only unregisters event listeners, while `disablePlugin` also releases permissions, audio and commands. Target: a per-plugin `PluginLifecycleRunner` (or state machine) used by both, with an explicit "release everything acquired" step (events, commands, permissions, audio, JDA listeners, classloader). Split `PluginManager` (~960 lines) into scanner/loader (jar → descriptor → instance), lifecycle runner, and registry/reload orchestration.
- [ ] **P2 — Identity = `id` everywhere.** `PluginManager.reloadPlugin` stores under `getName()`; `AudioServiceImpl.pluginGuilds` and log lines key by `getName()`; `SimplePermissionManager` logs by name. Grep `getName()` in core and switch keys to `getId()`. Decide with the user whether ids must be lowercase (language namespace lowercases, nothing else does).
- [x] **P3 — Classloader hardening.** (2026-09-20) `fr.farmvivi.fluxcord.api` added to `CORE_PACKAGES`; `getResourceAsStream` no longer uses the JarURLConnection cache (kept the jar locked on Windows after `close()`, blocking reload); template scope fixed. Left: fail fast in `loadPlugin` when a jar bundles api classes (now harmless: parent-first), and closing the classloader on every failure path (P1).
- [ ] **P4 — Dependants of failed plugins.** `DependencyResolver` only handles *missing* deps; a plugin whose hard dependency failed to load/enable is still enabled. Propagate failure along hard deps.
- [ ] **P5 — `PluginContext` exposes plugin-scoped adapters** (`getCommands()`, `getPermissions()`, `getLanguage()`, `getStorage()`) instead of only the shared managers, so namespacing can't be bypassed and `AbstractPlugin` stops building adapters itself. API addition; keep the old getters.

## 2. Boot & permissions

- [ ] **B1 — Operators are unreachable.** `PermissionDefault.OP` resolves through `SimplePermissionManager.isOperator`, which reads an `isOperator` flag that nothing writes. Result: `music.volume`, `music.admin` are denied to everyone except the console. Add (decide with the user): `permissions.operators` list in `config.yml`, `OP` ⇒ guild owner / `ADMINISTRATOR` mapping, an `op`/`deop` console+slash command.
- [ ] **B2 — `Fluxcord` bootstrapper → instance.** Replace the static fields/getters (unused outside the class) with a `FluxcordRuntime` object built by a small `main`; remove `System.exit` from helper methods (throw, exit only in `main`). Makes boot testable and reload (`PluginManager.reloadPlugins` reconnecting JDA) reviewable.
- [ ] **B3 — Configuration typing.** `CoreConfiguration` is read with string keys all over (`Fluxcord`, `StorageFactory`, `SimpleCommandService`...). Introduce typed config records (`DiscordConfig`, `StorageConfig`, `CommandsConfig`) parsed once, with validation in one place.

## 3. Commands

- [ ] **C1 — Split `SimpleCommandService`** (~840 lines): `CommandExecutor` (gating pipeline: enabled → guildOnly → permission → cooldown → events → execute → metrics), `JdaCommandMapper` (`createCommandData`/`buildOptionData`/subcommands), `CommandSynchronizer` (global/guild sync + debounce), `CooldownTracker`, keep `SimpleCommandService` as the façade implementing `CommandService`.
- [ ] **C2 — Parser loop semantics.** In `processCommand`, a `CommandParseException` falls through to the next parser instead of replying with a usage error; unknown commands are only logged at debug. Define the intended behaviour (reply `commands.messages.unknown_command`? usage on parse error?) and test it.
- [ ] **C3 — Autocomplete.** `buildOptionData` sets `setAutoComplete(true)` but nothing handles `CommandAutoCompleteInteractionEvent`. Either implement the handler in `CommandListener` (calling the option's `autocompleteProvider`) or remove the builder methods. Rename `OptionType2` → `OptionType` (API break; ask).
- [ ] **C4 — Command namespace collisions** across plugins: today the second `play` is dropped with a warning. Decide: reject at registration with a clear error, or allow per-plugin prefixes.
- [ ] **C5 — `CommandMessageBuilder`** (~610 lines) handles three transports with flags (`differ`, `ephemeral`); consider one `ReplyTarget` strategy per transport.

## 4. Events

- [x] **E1 — Dispatch semantics.** Done 2026-09-20: dispatch is polymorphic (concrete class first, then supertypes, per priority); `ignoreCancelled` now has Bukkit semantics (`true` = skipped once cancelled, default `false` = always called); handler lists are copy-on-write (registration from the main thread raced with `fireEvent` from storage/async threads); priority Javadoc fixed (LOWEST → MONITOR). Open: virtual threads for `fireEventAsync`.
- [ ] **E2 — JDA listener ergonomics.** Add `DiscordAPI.addEventListener(Plugin, Object...)` that registers on builder or JDA depending on state and removes on disable; fix `docs/*.md`, `plugin-template` and `README` which show the non-working `@EventHandler` on `MessageReceivedEvent`.

## 5. Storage

- [ ] **S1 — Managers as interfaces.** `DataStorageManager` / `BinaryStorageManager` are concrete classes in `fluxcord-api` (with loggers). Move implementation to core, keep interfaces in api.
- [ ] **S2 — Collapse the scoped views.** 4 plain + 4 plugin-prefixed views × data/binary = 16 near-identical classes. One generic scoped view with a key-prefix strategy.
- [ ] **S3 — Shared Gson configuration** (records, `Instant`, enums) for FILE and DB backends; document what value types are supported.
- [ ] **S4 — DB tests.** H2 (MySQL/PostgreSQL modes) or Testcontainers for `DatabaseDataStorage`; ask before adding the dependency.

## 6. i18n

- [ ] **L1 — Collapse the lookup cascade** in `SimpleLanguageManager.getString(locale, key)` (~300 lines of repeated steps) into an ordered list of sources; fire `StringRetrievalEvent` once; add `Optional<String> find(...)` to the api so "missing" isn't signalled by returning the key.

## 7. Audio

- [ ] **A1 — Key by plugin id** (see P2).
- [ ] **A2 — Extract the send strategy** (bypass vs mix, Opus rejection, priority fades) from `AudioPipeline` into a testable class; translate the French comments while there. Open question found 2026-09-20: bypass (single PCM source) ignores volume and fade — decide whether volume < 100 or an active fade should force the mixer path.
- [ ] **A3 — `ai-audio-plugin`** is a stub of TODOs; remove from the reactor or make it a real example — user decision.

## 8. Hygiene (do opportunistically inside the chantiers above, never as drive-by commits)

- Broad `catch (Exception)` → catch what the call can throw; never swallow without logging the cause.
- Null-checks on always-injected services (`if (eventManager != null)` ×20 in `PluginManager`) → make the constructor require them.
- French/English mix in comments and logs: new text in English; rewrite only what you touch.
- ~~`sonar-project.properties` says `sonar.java.source=17` while the build targets 25.~~ Fixed 2026-09-20; the Sonar workflow now also runs the tests so coverage reaches SonarCloud.
- The `Deploy` webhook step in `.github/workflows/docker-image-ci.yml` (and the `DEPLOY_WEBHOOK_URL` secret) is a leftover of the v2 VPS setup; deployments are now handled outside this repo. Remove the step.
- `docs/` describe intended behaviour; after each chantier update the relevant page.

## Decisions log

| Date | Decision | By |
|---|---|---|
| 2026-09-19 | Plan created; skills + Stop hook introduced; first chantier to be chosen after review. | user + Claude |
| 2026-09-20 | T0 done as a dedicated workflow + JaCoCo report-only (no threshold), CI also on Dependabot PRs. Keep working on `develop`; branches optional. | user |
| 2026-09-20 | E1: polymorphic dispatch and Bukkit `ignoreCancelled` semantics (API behaviour change; no plugin in the repo used `ignoreCancelled`). | user |
