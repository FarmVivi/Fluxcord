---
name: fluxcord-events
description: Expert on Fluxcord's two event buses - the internal SimpleEventManager (@EventHandler on api Event subclasses, priorities, Cancellable, async) versus raw JDA events (ListenerAdapter on JDABuilder/JDA). Use when adding/handling events, when a listener "never fires", when a plugin needs Discord events (messages, voice, buttons), or when refactoring event dispatch/registration.
paths:
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/event/**
  - fluxcord-api/src/main/java/fr/farmvivi/fluxcord/api/event/**
  - fluxcord-api/src/main/java/fr/farmvivi/fluxcord/api/**/events/**
---

# Events — two separate buses

## Bus 1: internal `EventManager` (`core/event/SimpleEventManager.java`, ~620 lines)
- Dispatches only subclasses of `fr.farmvivi.fluxcord.api.event.Event`. Families: `plugin/events` (Loading/Loaded/Enable/Enabled/Disable/Disabled/LifecycleChange), `storage/events`, `storage/binary/events`, `permissions/events`, `language/events` (`StringRetrievalEvent` can override a translation), `audio/events`, `command/event` (`CommandExecuteEvent` cancellable, `CommandExecutedEvent`).
- Registration: `eventManager.registerListener(listenerObject, plugin)` scans `@EventHandler` methods with exactly one parameter that is an `Event` subclass. `@EventHandler(priority = EventPriority.X, ignoreCancelled = bool)`; priorities `LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR` are called in that order (so `LOWEST` first, `MONITOR` last — the Javadoc on `EventHandler` says "higher priority called first", which contradicts the loop in `fireEvent`; the loop wins).
- `fireEvent(event)` is **synchronous on the caller's thread**, returns the event, and dispatches only to handlers registered for `event.getClass()` **exactly** — no polymorphism: a handler on `PluginEvent` does not receive `PluginEnabledEvent`. Handler exceptions are caught and logged (`Throwable`).
- `fireEventAsync(event)` submits to an unbounded cached thread pool (`FluxEvent-*` threads); no ordering guarantees.
- Also an `EventRegistry` (`registerEventType`, `getEventTypeInfo`...) for declaring custom event types with a description; purely informational.
- `unregisterAll(plugin)` is called by `PluginManager` on disable; `shutdown()` stops the executor.

## Bus 2: JDA events
`SimpleEventManager.registerListener` **ignores** methods whose parameter isn't an api `Event` (warning "does not have exactly one parameter / is not an Event"). To receive Discord events a plugin registers a `net.dv8tion.jda.api.hooks.ListenerAdapter`:
```java
// onEnable (pre-connect): goes on the builder, JDA picks it up at connect
getContext().getDiscordAPI().getBuilder().addEventListeners(listener);
// hot-reload case: JDA already connected
JDA jda = getContext().getDiscordAPI().getJDA();
if (jda != null) jda.addEventListener(listener);
// onDisable: remove it, or the old classloader leaks and events hit dead code
if (jda != null) jda.removeEventListener(listener);
```
Reference implementation: `plugins/music-plugin/.../MusicPlugin.java` (`onEnable`, button/modal/ready/voice listeners). The core itself uses this bus for commands (`core/command/listener/CommandListener`).

**Stale docs**: `docs/core-features.md`, `docs/plugin-development.md`, `docs/plugins/template-quickstart.md`, `plugin-template/README.md` and `plugin-template/.../TemplatePlugin.java` / `events/ExampleEventListener.java` show `@EventHandler public void onMessageReceived(MessageReceivedEvent)`. That never fires. Fix them when you touch this area (plan item E2).

## Gotchas
- Cancellable: `event instanceof Cancellable`; `fireEvent` re-reads `isCancelled()` after each handler and skips handlers without `ignoreCancelled = true`.
- Registering the same listener object twice (even from another plugin) is refused with a warning.
- Events fired during `onLoad` (e.g. `PluginLoadingEvent`) can only be heard by plugins loaded earlier.
- Intents: any JDA event needing a privileged intent (message content, members, presence) must be enabled on `getBuilder()` **before** connect, i.e. in `onPreEnable`.

## Testing
No tests. `SimpleEventManager` is pure Java → easy unit tests (priority order, cancellation, exact-class dispatch, unregisterAll). Write them before changing dispatch (plan item E1).

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit.

## Known issues / open questions
- E1: exact-class dispatch — decide with the user whether to support supertype handlers (walk `getSuperclass()`/interfaces up to `Event`, cache per type).
- E2: fix stale docs/template showing `@EventHandler` on JDA events; consider a small `DiscordAPI.addListener(plugin, ListenerAdapter)` helper that also removes listeners on disable (would remove the biggest reload footgun).
- Priority semantics documented backwards in `EventHandler` Javadoc.
- Unbounded async pool with no naming/metrics; consider virtual threads (Java 25).
