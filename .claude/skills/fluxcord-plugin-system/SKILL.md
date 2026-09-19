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
| `core/plugin/PluginClassLoader.java` | `URLClassLoader`, child-first for classes/resources **except** `CORE_PACKAGES` (`fr.farmvivi.fluxcord.core`, slf4j, `net.dv8tion.jda.api`, snakeyaml, gson, HikariCP, AWS SDK) which are parent-first. |
| `core/plugin/PluginDescriptor.java` | record from `plugin.yml`: `id, name, main, version, description, authors, dependencies, softDependencies`. |
| `core/plugin/DependencyResolver.java` | topological order from descriptors; `getMissingDependencies()` → those plugins are put in `failedPlugins`. |
| `core/plugin/PluginConfiguration.java` | extends `YamlConfiguration`; copies default `config.yml` from the jar to `plugins/<id>/config.yml`; `initializeMigration(plugin)` uses `Plugin.getMigrationClass()` or the plugin itself if it implements `ConfigurableMigrationPlugin`, driven by `config_version`. |
| `core/plugin/PluginContextImpl.java` | the `PluginContext` handed to `onLoad`: id/name/version, logger named after the id, every core service, data folder `plugins/<id>`. |
| `api/plugin/AbstractPlugin.java` | base class; `onLoad` captures services and builds the five `Plugin*Adapter`s (namespaced by id). |
| `api/plugin/PluginLifecycle.java` | `DISCOVERED → LOADED → PRE_ENABLING → ENABLING → POST_ENABLING → ENABLED → PRE_DISABLING → DISABLING → POST_DISABLING → DISABLED`, plus `ERROR`. |

## Boot flow (called from `Fluxcord.startBot`)
`loadPlugins()` = `scanPlugins()` (parse every `plugins/*.jar` plugin.yml) → resolver order → `loadPlugin(jarPath)` for each → `plugins.put(id, plugin)`.
`loadPlugin` per jar: new `PluginClassLoader` → instantiate `main` → fire `PluginLoadingEvent` → build `PluginConfiguration` + `PluginContextImpl` → `plugin.setLifecycle(LOADED)`; `plugin.onLoad(ctx)` → `pluginConfig.initializeMigration(plugin)` → load `lang/*.yml` from the jar then from `plugins/<id>/lang/` into namespace `id.toLowerCase()` → fire `PluginLoadedEvent`.
Then, phase by phase over *all* plugins in dependency order: `preEnablePlugins()` (before JDA connects — plugins may still edit `JDABuilder`), `enablePlugins()`, `postEnablePlugins()` (sets `ENABLED`, fires `PluginEnabledEvent`). Each phase only touches plugins in the *previous* state, so a failure in one phase silently drops the plugin from the next ones (it is added to `failedPlugins`, state `ERROR`).

Shutdown: `close()` = `preDisablePlugins()` → `disablePlugins()` → `postDisablePlugins()` → `cleanupResources()` (unregister event listeners, close classloaders, clear maps).

## Gotchas / invariants
- **Two parallel lifecycle paths**: the phased `*Plugins()` methods used at boot/shutdown, and the single-plugin `enablePlugin()` / `disablePlugin()` (from `PluginLoader`) used by `reloadPlugin`. They differ: `disablePlugin()` unregisters events, permissions, audio connections and commands; the phased shutdown path only unregisters event listeners in `cleanupResources()`. Keep both in sync until they are merged (plan item P1).
- `reloadPlugin()` stores the new instance under `plugin.getName()` while everything else is keyed by `id` (`PluginManager.reloadPlugin`). Bug — use the id.
- `reloadPlugins()` (all) disconnects and reconnects JDA; it relies on `DiscordAPI.connect()` being re-entrant.
- Plugin classloader is closed on reload/shutdown; any thread or JDA listener the plugin left registered keeps the old classes alive (leak + `ClassCastException` on reload). JDA listeners added by plugins are **not** removed by the core — plugins must do it in `onDisable`.
- `PluginContextImpl` gives the *shared* service instances; namespacing is done by the adapters in `AbstractPlugin`, not by the context. A plugin can bypass namespacing by calling `context.getCommandService()` directly.
- Language namespace is registered twice defensively (`onLoad` via `PluginLanguageAdapter`, then `loadPlugin`). Harmless but a sign the responsibility is unclear.
- `System.exit` is never called here (only in `Fluxcord`); errors are logged and the plugin goes `ERROR`. Check the log line `Plugin loading complete: X loaded successfully, Y failed`.

## Testing
- Only `PluginConfigurationTest` covers this package. There is no fixture for a plugin jar; for `PluginManager` tests, build a minimal jar in a temp dir (plugin.yml + a compiled `Plugin` class from the test classpath) or refactor `loadPlugin` to accept a descriptor + class for unit tests.
- Smoke: `/verify --smoke` with the example plugins (`examples/plugins/*`) copied into `fluxcord-core/run/plugins/`.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit. Line numbers intentionally omitted (file will move a lot during the P1 chantier); grep method names instead.

## Known issues / open questions
- P1 (plan): merge the two lifecycle paths into one per-plugin state machine, and make disable release *everything* the plugin acquired.
- `reloadPlugin` id/name key mismatch (see gotchas).
- `loadPlugin` catches `Exception` broadly and returns `null`; the classloader created before the failure is not closed on most paths.
- Should `failedPlugins` block dependants? Currently a dependant of a failed plugin is still enabled (only *missing* deps are handled by the resolver).
