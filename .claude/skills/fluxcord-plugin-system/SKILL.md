---
name: fluxcord-plugin-system
description: Expert on Fluxcord's plugin engine in fluxcord-core - PluginManager, PluginClassLoader, PluginDescriptor/DependencyResolver, PluginConfiguration migration, PluginContextImpl, lifecycle states and hot reload. Use when touching plugin loading/enabling/disabling/reloading, classloader isolation, plugin.yml parsing, plugin config migration, or debugging "plugin not loaded / not disabled / resources leaked".
paths:
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/plugin/**
  - fluxcord-api/src/main/java/fr/farmvivi/fluxcord/api/plugin/**
---

# Plugin system (core side)

For *writing* a plugin see `fluxcord-plugin-dev`; this skill is about the engine.

## Map
| File | Role |
|---|---|
| `core/plugin/PluginManager.java` (~960 lines) | implements `api.plugin.PluginLoader` + `Closeable`. Scans, loads, orders, enables, disables, reloads. Holds `plugins`, `classLoaders`, `pluginDescriptors`, `pluginJarPaths`, `failedPlugins` maps keyed by plugin **id**. |
| `core/plugin/PluginClassLoader.java` | `URLClassLoader`, child-first for classes/resources **except** `CORE_PACKAGES` (`fr.farmvivi.fluxcord.api`, `fr.farmvivi.fluxcord.core`, slf4j, `net.dv8tion.jda.api`, snakeyaml, gson, HikariCP, AWS SDK) which are parent-first. |
| `core/plugin/PluginDescriptor.java` | record from `plugin.yml`: `id, name, main, version, description, authors, dependencies, softDependencies`. |
| `core/plugin/DependencyResolver.java` | topological order from descriptors; `getMissingDependencies()` → those plugins are put in `failedPlugins`. |
| `core/plugin/PluginConfiguration.java` | extends `YamlConfiguration`; copies default `config.yml` from the jar to `plugins/<id>/config.yml`; `initializeMigration(plugin)` uses `Plugin.getMigrationClass()` or the plugin itself if it implements `ConfigurableMigrationPlugin`, driven by `config_version`. |
| `core/plugin/PluginContextImpl.java` | the `PluginContext` handed to `onLoad`: id/name/version, logger named after the id, every core service, data folder `plugins/<id>`, and (P5) the five plugin-scoped views `getCommands()/getPermissions()/getLanguage()/getStorage()/getBinaryStorage()` — built by `PluginManager` from the **descriptor** id/name because the plugin instance cannot answer `getId()` before `onLoad`. |
| `api/plugin/AbstractPlugin.java` | base class; `onLoad` captures services and takes the adapters from the context (builds its own only when the context returns null — mocked contexts in unit tests). |
| `api/plugin/PluginLifecycle.java` | `DISCOVERED → LOADED → PRE_ENABLING → ENABLING → POST_ENABLING → ENABLED → PRE_DISABLING → DISABLING → POST_DISABLING → DISABLED`, plus `ERROR`. |

## Boot flow (called from `Fluxcord.startBot`)
`loadPlugins()` = `scanPlugins()` (parse every `plugins/*.jar` plugin.yml) → resolver order → `loadPlugin(jarPath)` for each → `plugins.put(id, plugin)`.
`loadPlugin` per jar: new `PluginClassLoader` → instantiate `main` → fire `PluginLoadingEvent` → build `PluginConfiguration` + `PluginContextImpl` → `plugin.setLifecycle(LOADED)`; `plugin.onLoad(ctx)` → `pluginConfig.initializeMigration(plugin)` → load `lang/*.yml` from the jar then from `plugins/<id>/lang/` into namespace `id.toLowerCase()` → fire `PluginLoadedEvent`.
Then, phase by phase over *all* plugins in dependency order: `preEnablePlugins()` (before JDA connects — plugins may still edit `JDABuilder`), `enablePlugins()`, `postEnablePlugins()` (sets `ENABLED`, fires `PluginEnabledEvent`). Each phase only touches plugins in the *previous* state. A failure (load or any phase) goes through `markFailed(id)` (P4): `ERROR`, `releaseResources`, and every *hard* dependant (transitively, via the descriptors) is failed the same way before its turn comes — soft dependants carry on. `getFailedPlugins()` lists them.

Shutdown: `close()` = `preDisablePlugins()` → `disablePlugins()` → `postDisablePlugins()` → `cleanupResources()` (unregister event listeners, close classloaders, clear maps).

## Gotchas / invariants
- **Two lifecycle paths, one implementation** (P1 done 2026-09-20): the phased `*Plugins()` methods (boot/shutdown, needed for the pre-connect/post-connect split) and `enablePlugin()`/`disablePlugin()` (reload) both go through `executeLifecyclePhase`, `markEnabled`, `markDisabled` and `releaseResources` (events, permissions, audio, commands). Add new per-plugin cleanup to `releaseResources` only.
- `reloadPlugins()` (all) disconnects and reconnects JDA; it relies on `DiscordAPI.connect()` being re-entrant.
- Plugin classloader is closed on reload/shutdown; any thread or JDA listener the plugin left registered keeps the old classes alive (leak + `ClassCastException` on reload). JDA listeners added by plugins are **not** removed by the core — plugins must do it in `onDisable`.
- `PluginContextImpl` still exposes the *shared* managers next to the scoped views; a plugin can bypass namespacing by calling `context.getCommandService()` directly (kept on purpose for cross-plugin integration).
- Language namespace is registered twice defensively (`onLoad` via `PluginLanguageAdapter`, then `loadPlugin`). Harmless but a sign the responsibility is unclear.
- `PluginConfiguration` ignores the manager'"'"'s `pluginsFolder`: it resolves `plugins/<id>/config.yml` from `-Dplugins.dir` / `DISCORD_PLUGINS_DIR` / cwd `plugins`, while `PluginContext.getDataFolder()` uses the manager'"'"'s folder. Same place only because `Fluxcord` passes `new File("plugins")`.
- `System.exit` is never called here (only in `Fluxcord`); errors are logged and the plugin goes `ERROR`. Check the log line `Plugin loading complete: X loaded successfully, Y failed`.

## Testing
- Covered: `PluginConfigurationTest`, `PluginDescriptorTest`, `DependencyResolverTest`, `PluginClassLoaderTest`, **`PluginManagerTest`** (11: boot phases, dependency order, missing dep, bad jars, phase failure → ERROR, lifecycle events, veto, reload, shutdown order). Fixtures in `core/testing`: `PluginJars.plugin(dir, id, extraYaml)` builds a real jar whose main is `com.example.fixture.FixturePlugin` (extends `AbstractPlugin`), and `PluginCalls` records phases — it lives in a **core** package so the child loader shares it (a fixture-package recorder would be a different class per loader). `-Dfixture.fail=id:phase` makes a phase throw. Tests must set `plugins.dir` (see gotcha below).
- Smoke: `/verify --smoke` with the example plugins (`examples/plugins/*`) copied into `fluxcord-core/run/plugins/`.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit. Line numbers intentionally omitted (file will move a lot during the P1 chantier); grep method names instead.
- 2026-09-20: `DependencyResolver` returned the load order reversed (post-order DFS already yields dependencies first; the `Collections.reverse` was wrong) and iterated a `HashMap` (non-deterministic). Fixed + `DependencyResolverTest`. Nothing noticed it because no shipped plugin declares a dependency.
- 2026-09-20: `PluginClassLoaderTest` builds a real jar in a `@TempDir` by copying bytecode of classes from the test classpath (`com.example.fixture.SamplePluginClass`); fixture classes must live outside `fr.farmvivi.fluxcord.{api,core}` or they are parent-first by design. Reading resources through `url.openStream()` caches the `JarFile` and locks the jar on Windows even after `close()` — use `setUseCaches(false)`.
- 2026-09-20 (T1): `PluginManagerTest` confirmed and fixed: `reloadPlugin` stored the new instance under `getName()` (so `getPlugin(id)` was null after a reload and a second entry appeared), and `loadPlugin` left the class loader open when the main class failed to load (jar locked on Windows). Both fixed.

- 2026-09-20 (P4/P5): a dependant of a failed plugin used to be enabled anyway (and a dependant of a plugin that failed to *load* was loaded too); `markFailed` cascades now. Adapters (`PluginLanguageAdapter`, `PluginDataStorageAdapter`, `PluginBinaryStorageAdapter`) got `(pluginId, …)` constructors because the context is built before `onLoad`.

## Known issues / open questions
- P3 done except the fail-fast on bundled api classes (now harmless).
