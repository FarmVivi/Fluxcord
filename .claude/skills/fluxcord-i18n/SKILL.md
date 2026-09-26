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
- `api/language/PluginLanguageAdapter` — built by `PluginManager` for the `PluginContext` (`getLanguage()`); namespace = plugin id; prefixes keys with `<id>:` so plugins call `getLanguage().getString("player.now_playing", title)`. Also `getDefaultLocale()` and the escape hatch `getLanguageManager()`.
- `core/language/SimpleLanguageManager` (~200 lines, L1 done 2026-09-20) — one map `namespace → locale → flatKey → string`; namespaces are **lower-cased** on register/load/lookup (plugin ids may have upper-case letters). Everything arrives through `loadLanguage` in load order and later loads override earlier ones key by key: core bundled `en-US`/`fr-FR` (constructor) → `./lang/*.yml` (`FluxcordRuntime`); plugin jar `lang/*.yml` → `plugins/<id>/lang/*.yml` (`PluginManager.loadPlugin`).
- `core/language/LanguageFiles` — the single YAML loader: `parse(Reader)` (nested → dotted keys, leaves `String.valueOf`, null leaves skipped), `localeOf("fr_FR.yml")`, `loadResource/loadFolder/loadJar(manager, namespace, …)`; a broken file is logged and skipped.
- Resources: `fluxcord-core/src/main/resources/lang/{en-US,fr-FR}.yml` (core namespace), `plugins/music-plugin/src/main/resources/lang/*.yml`.

## Lookup cascade (`SimpleLanguageManager.lookup`)
Key `ns:actual` (default ns `core`). Unregistered namespace → warning + returns the raw key. Candidates, without repeats: requested locale → a same-language locale that has the key (the configured default locale first if it shares the language, else the first found) → configured default locale → `en-US`. First hit wins; otherwise the raw key. One `StringRetrievalEvent` per lookup (hit or miss, with the args when given and the locale that answered), listeners may `setValue` to override. The args overload then applies `MessageFormat` (`{0}`); lone apostrophes are doubled automatically by `escapeApostrophes` (since 2026-09-20 — `l'utilisateur {1}` used to lose its placeholders), `{` in literal text must still be quoted.

`getString` returning exactly the key is the "missing" signal (used by the args overload and by callers) — so a translation whose value equals its key is indistinguishable from a miss.

## Gotchas
- Locale of a command reply comes from `CommandContext.getLocale()` (user's Discord locale for slash, default for text/console).
- Adding a key: add it to **both** `en-US.yml` and `fr-FR.yml` of the right namespace; en-US is the ultimate fallback so it must be complete.
- Runtime overrides live outside the jar: `./lang/` (core) and `plugins/<id>/lang/` (plugin) — useful for hosters, but a stale override silently shadows a fixed default.

## Testing
`SimpleLanguageManagerTest` (17: bundled resources, cascade order, missing-key contract, `loadLanguage` merge, case-insensitive namespaces, MessageFormat quirks, event override, single firing), `LanguageFilesTest` (4), `PluginManagerTest.pluginStringsAreReachableThroughTheContextWhateverTheIdCase`.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit.
- 2026-09-20: Characterized without finding bugs. The cascade has 6 steps, not 4 (default locale after en-US). Non-string YAML leaves become `String.valueOf` (`true`, `12`), sections are not strings (`getString("permissions")` misses).

- 2026-09-20 (L1): real bug found — namespace case mismatch between `PluginLanguageAdapter` (id verbatim) and `PluginManager` (`id.toLowerCase()`); harmless today because every first-party id is lower-case. The old "resources vs runtime" split was just two layers of the same map; a single map with load-order override is equivalent and testable.

- 2026-09-20: every French string with an apostrophe before a placeholder (`de l'utilisateur {1}`) rendered `{1}` literally — MessageFormat quoting. Fixed at the formatting layer rather than in each YAML file.

## Known issues / open questions
- Missing-key signalling by string equality; consider `Optional<String> find(...)` in the api (API addition, backwards compatible).

- 2026-09-26: the MessageFormat quoting rule bites in **both** directions, and it is worth a test rather than a habit. A value the code formats (`getString(key, args)`) must double its apostrophes or the placeholders after it are swallowed; a value the code never formats must **not**, or the user reads `n''a pas` literally. `ai-audio-plugin`'s `LanguageFilesTest` pins this: a `FORMATTED_KEYS` set, single quotes forbidden inside it, doubled quotes forbidden outside it, plus a check that every value containing a `{0}` is listed. Copy it into any module whose French strings take arguments.

- 2026-09-26: **an unquoted YAML key can stop being a string.** snakeyaml implements YAML 1.1, where `off`, `on`, `yes`, `no`, `true`, `false` and bare numbers are scalars of their own type — as *keys* too. `music.command.loop.mode.off` was correctly translated in both locales and never resolved, because the key flattened to `...mode.false` through `String.valueOf`; `/loop` offered its users a raw key as a choice label. Quote such keys (`"off":`). A repository-wide scan found exactly this one case, and the guard now lives in every module's `LanguageFilesTest`.
- 2026-09-26: **`PluginLanguageAdapter` now warns on a miss** (`no translation for '<key>'; the key itself will be shown to the user`) and its debug line prints the resolved value. Before, it printed the *namespaced key* with an arrow, as if that were the result — which reads exactly like a failed lookup even when the lookup worked, and sent a reader hunting a bug that was not there. With the warning in place, a single smoke run surfaced two real misses (`music.command.loop.mode.off`, `music.error.guild_only`) that no test had caught. Grepping a log for that warning is now the cheapest i18n audit there is.
- 2026-09-26: the MessageFormat quoting bug was **repository-wide**: 24 formatted values carried a lone apostrophe, in `fluxcord-core`'s own `fr-FR.yml` (its cooldown and execution-error messages), the commands example and the template. Worst shape is `'{0}'`, common in the template: MessageFormat reads it as a *literal* `{0}`, so the user saw the placeholder instead of the value. All fixed, and the check is now in the five `LanguageFilesTest`s plus `CoreLanguageResourcesTest`.
- 2026-09-26: the three checks every language-file test should carry — keys are strings, both locales declare the same keys with the same placeholders, and no formatted value has a lone apostrophe — cannot be shared between modules (no test artifact is published), so they are duplicated on purpose. When adding a module, copy `plugins/ai-audio-plugin`'s test.
