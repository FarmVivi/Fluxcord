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
Key `ns:actual` (default ns `core`). Unregistered namespace → warning + returns the raw key. Then: 1 runtime[locale] → 2 resources[locale] → 2b same-language variant (`fr` → `fr-FR`, `fr-FR` → `fr-CA`, first found) → 3/4 the configured default locale (runtime, resources; skipped when equal) → 5/6 en-US (runtime, resources; skipped when the locale is already English) → raw key. Decision 2026-09-20: the configured default comes **before** en-US (a German user of a `fr-FR` bot gets French; en-US remains the final net). `resources` only ever holds the core `en-US`/`fr-FR` bundled files; plugin jar strings and every runtime file go through `loadLanguage` into `runtime` (merged with `putAll`, so `plugins/<id>/lang` overrides jar keys one by one). Each hit fires `StringRetrievalEvent` (listeners may `override` the value). The `args` overload calls the plain one, fires the event **again** with args, then `MessageFormat.format` (`{0}`, `{1}` — beware MessageFormat quirks: single quotes must be doubled `''`, and `{` in literal text must be quoted).

`getString` returning exactly the key is the "missing" signal (used by the args overload and by callers) — so a translation whose value equals its key is indistinguishable from a miss.

## Gotchas
- Locale of a command reply comes from `CommandContext.getLocale()` (user's Discord locale for slash, guild locale for text, default for console) — check `SimpleCommandContext` before assuming.
- Adding a key: add it to **both** `en-US.yml` and `fr-FR.yml` of the right namespace; en-US is the ultimate fallback so it must be complete.
- Runtime overrides live outside the jar: `./lang/` (core) and `plugins/<id>/lang/` (plugin) — useful for hosters, but a stale override silently shadows a fixed default.
- Namespace registration happens in two places (adapter in `onLoad`, and `PluginManager.loadPlugin` "defensively").

## Testing
`SimpleLanguageManagerTest` (16 tests): bundled resources, cascade order (variant, en-US, default locale), missing-key contract, `loadLanguage` merge, MessageFormat quirks (`'` swallows placeholders, `{0,number,#}` avoids grouping), event override and double firing. Safety net for L1.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit.
- 2026-09-20: Characterized without finding bugs. The cascade has 6 steps, not 4 (default locale after en-US). Non-string YAML leaves become `String.valueOf` (`true`, `12`), sections are not strings (`getString("permissions")` misses).

## Known issues / open questions
- L1: `getString(locale, key)` is ~300 lines of copy-pasted cascade steps (each with its own debug log + event firing). Collapse into an ordered list of lookup sources + one loop; fire `StringRetrievalEvent` once.
- Missing-key signalling by string equality; consider `Optional<String> find(...)` in the api (API addition, backwards compatible).
- Consider `PluginContext` exposing the plugin's adapter directly so plugins never see unprefixed namespaces.
