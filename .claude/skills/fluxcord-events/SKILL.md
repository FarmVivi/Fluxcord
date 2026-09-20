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
- Registration: `eventManager.registerListener(listenerObject, plugin)` scans `@EventHandler` methods with exactly one parameter that is an `Event` subclass. `@EventHandler(priority = EventPriority.X, ignoreCancelled = bool)`; priorities run `LOWEST → LOW → NORMAL → HIGH → HIGHEST → MONITOR` (higher priority runs later and has the final say). `ignoreCancelled = true` = **skip me once the event is cancelled** (Bukkit semantics, since 2026-09-20); default `false` = always called. Only `public` methods are scanned (`getMethods()`); a listener with zero valid handlers is not tracked at all.
- `fireEvent(event)` is **synchronous on the caller's thread** and returns the event. Dispatch is **polymorphic**: for each priority, handlers of the concrete class run first, then those of each supertype (class or interface extending `Event`, nearest first; hierarchy cached per class). Handler exceptions are caught and logged (`Throwable`). Handler lists are `CopyOnWriteArrayList` — a listener registered mid-dispatch is skipped for the priority being iterated but still sees the current event at later priorities.
- `fireEventAsync(event)` submits to an unbounded cached daemon pool (threads named `EventManager-AsyncWorker`); no ordering guarantees.
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

Docs and template were fixed on 2026-09-20 (E2); if a `@EventHandler` on a JDA event type reappears anywhere, it is wrong.

## Gotchas
- Cancellable: `event instanceof Cancellable`; `fireEvent` re-reads `isCancelled()` after each handler, so a later handler can un-cancel. `MONITOR` gets no special treatment (it can still cancel).
- Registering the same listener object twice (even from another plugin) is refused with a warning.
- Events fired during `onLoad` (e.g. `PluginLoadingEvent`) can only be heard by plugins loaded earlier.
- Intents: any JDA event needing a privileged intent (message content, members, presence) must be enabled on `getBuilder()` **before** connect, i.e. in `onPreEnable`.

## Testing
`SimpleEventManagerTest` (22 tests): registration rules, priority order, polymorphic dispatch, cancellation, unregister/unregisterAll, registry, async. Uses an inner `StubPlugin implements Plugin` (no Mockito needed); the auto-registration test relies on the stub living in the same package as the test events.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit.
- 2026-09-20: E1 done with the user: polymorphic dispatch + Bukkit `ignoreCancelled`. Handler storage was `EnumMap`+`ArrayList` mutated by `registerListener` while `fireEvent` copied it from other threads → now `ConcurrentHashMap`+`CopyOnWriteArrayList`. Walking a class hierarchy: `getSuperclass()` is `null` for interfaces and `ArrayDeque` rejects `null`.
- 2026-09-20: `EventManager.hasListeners(Class)` (api default `true`, real answer in `SimpleEventManager`, hierarchy-aware). `fireEvent` already returns fast with no handler; the guard only saves *building* the event, so it is used on hot paths: audio frame (50/s), storage get/set/remove, `StringRetrievalEvent`, `PermissionCheckEvent`. Don't sprinkle it on rare events.
- 2026-09-20 (E2): `DiscordAPI.addEventListeners(plugin, ...)`/`removeEventListeners(plugin)` + `AbstractPlugin.addDiscordListeners(...)`; the example/ai-audio plugins had `@EventHandler` on JDA events that never fired (fixed); `AudioExamplePlugin` never registered its Fluxcord handlers either (now `eventManager.registerListener(this, this)`).

## Known issues / open questions
- Unbounded async pool with no naming/metrics; consider virtual threads (Java 25).
