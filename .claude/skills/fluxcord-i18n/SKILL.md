---
name: fluxcord-i18n
description: Expert on Fluxcord internationalisation - SimpleLanguageManager, namespaces (core vs plugin id), lang/*.yml resources and runtime overrides, locale fallback cascade, MessageFormat placeholders, StringRetrievalEvent overrides, PluginLanguageAdapter. Use when adding/changing user-facing strings, adding a language, debugging a raw key showing in Discord, or refactoring the language manager.
paths:
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/language/**
  - fluxcord-api/src/main/java/fr/farmvivi/fluxcord/api/language/**
  - "**/src/main/resources/lang/*.yml"
---

# i18n

## Rule
User-facing text (replies, embeds, permission descriptions, help) goes through `lang/*.yml` keys, never literals. Log messages don't (English preferred for new ones; the codebase still has French comments/logs — leave them unless you rewrite the method).

## Map
- `api/language/LanguageManager` — `getString(key)`, `getString(key, args...)`, `getString(locale, key)`, `getString(locale, key, args...)`, `registerNamespace(ns)`, `loadLanguage(ns, locale, Map<String,String>)`, `getDefaultLocale()`, `getAvailableLocales()`.
- `api/language/PluginLanguageAdapter` — created in `AbstractPlugin.onLoad`; namespace = `plugin.getId()`; prefixes keys with `<id>:` so plugins call `getPluginLanguageManager().getString("player.now_playing", title)`.
- `core/language/SimpleLanguageManager` (~600 lines) — two maps `translations` (runtime `lang/` folder overrides) and `defaultTranslations` (from classpath / jar resources), both `namespace → locale → flatKey → string`. Nested YAML is flattened to dotted keys (`commands.messages.cooldown`).
- `core/language/LanguageFileLoader` — loads the **core** runtime folder `./lang/*.yml` at boot (`Fluxcord.createLanguageServices`). Plugin languages are loaded by `PluginManager.loadPlugin` (jar `lang/*.yml` then `plugins/<id>/lang/*.yml`), namespace `id.toLowerCase()`.
- Resources: `fluxcord-core/src/main/resources/lang/{en-US,fr-FR}.yml` (core namespace), `plugins/music-plugin/src/main/resources/lang/*.yml`.

## Lookup cascade (`SimpleLanguageManager.getString(locale, key)`)
Key `ns:actual` (default ns `core`). Unregistered namespace → warning + returns the raw key. Then: 1 runtime[locale] → 2 resources[locale] → 2b same-language variant (`fr` → `fr-FR`) → 3 runtime[en-US] → 4 resources[en-US] → raw key. Each hit fires `StringRetrievalEvent` (listeners may `override` the value). The `args` overload calls the plain one, fires the event **again** with args, then `MessageFormat.format` (`{0}`, `{1}` — beware MessageFormat quirks: single quotes must be doubled `''`, and `{` in literal text must be quoted).

`getString` returning exactly the key is the "missing" signal (used by the args overload and by callers) — so a translation whose value equals its key is indistinguishable from a miss.

## Gotchas
- Locale of a command reply comes from `CommandContext.getLocale()` (user's Discord locale for slash, guild locale for text, default for console) — check `SimpleCommandContext` before assuming.
- Adding a key: add it to **both** `en-US.yml` and `fr-FR.yml` of the right namespace; en-US is the ultimate fallback so it must be complete.
- Runtime overrides live outside the jar: `./lang/` (core) and `plugins/<id>/lang/` (plugin) — useful for hosters, but a stale override silently shadows a fixed default.
- Namespace registration happens in two places (adapter in `onLoad`, and `PluginManager.loadPlugin` "defensively").

## Testing
No tests. `SimpleLanguageManager` is pure Java: test the cascade order, the `fr`→`fr-FR` variant step, the missing-key contract, and the double event firing (plan item L1).

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit.

## Known issues / open questions
- L1: `getString(locale, key)` is ~300 lines of copy-pasted cascade steps (each with its own debug log + event firing). Collapse into an ordered list of lookup sources + one loop; fire `StringRetrievalEvent` once.
- Missing-key signalling by string equality; consider `Optional<String> find(...)` in the api (API addition, backwards compatible).
- Consider `PluginContext` exposing the plugin's adapter directly so plugins never see unprefixed namespaces.
