---
name: refactor-module
description: Run one refactoring "chantier" on a Fluxcord subsystem (plugin lifecycle, commands, events, storage, i18n, audio, boot) safely - characterization tests first, small verified steps, plan kept up to date. Use when asked to refactor, clean up, restructure, de-duplicate or fix design problems in a module, or to pick the next item from docs/refactoring-plan.md.
argument-hint: "[plan-item-id or module name]"
---

# Refactor a module — working protocol

Context: v3 (`develop`) was largely generated with Codex and never fully tested. Code "works-ish", systems are inconsistent with each other, and the test suite is thin (7 test classes). The main risk of a refactor here is silently changing behaviour nobody ever verified. This protocol keeps that risk low.

Target: **$ARGUMENTS** (if empty: propose the highest-priority unchecked item from the plan and ask).

## 0. Load the domain expert
Invoke the matching reference skill first: `fluxcord-plugin-system`, `fluxcord-commands`, `fluxcord-events`, `fluxcord-storage`, `fluxcord-i18n`, `fluxcord-audio`, `fluxcord-boot`, `fluxcord-plugin-dev`. Read its *Known issues* — the chantier probably starts there.

## 1. Scope & decisions (ask, don't guess)
- Read the item in `docs/refactoring-plan.md`. Restate the goal, the files involved, and what is explicitly **out of scope**.
- Any choice with more than one reasonable design (API break vs. compatibility shim, keep/delete a feature, naming) → **ask the user with AskUserQuestion** before coding. Changes to `fluxcord-api` always get asked: plugins outside this repo may depend on them.
- Check who calls what: `grep -rn` across `fluxcord-core`, `plugins/`, `examples/`, `plugin-template/` and `docs/`. Docs and the template are consumers too.

## 2. Characterize before changing
- Write (or extend) JUnit 5 + Mockito tests in `fluxcord-core/src/test` that pin the *current* observable behaviour you intend to keep. Run them green **before** touching production code.
- If the current behaviour is a bug (e.g. resources not released), write the test for the *intended* behaviour, watch it fail, then fix.
- Prefer testing through the `fluxcord-api` interfaces over internals so the test survives the refactor.

## 3. Change in small verified steps
- One conceptual step at a time (extract class, rename, remove a duplicate path...). After each: `/verify` (at least the module tests).
- Keep old + new paths alive for one step at most; delete the old one within the same chantier — the goal is *less* code. Don't leave `*2`, `Legacy*`, duplicate `Simple*` classes behind.
- Consistency rules to apply while you are in there:
  - identity: plugins are keyed by `id` (from `plugin.yml`), never by display `name`;
  - whatever `onEnable` acquires is released on disable/reload (events, commands, permissions, audio, JDA listeners, classloader);
  - user-facing strings via `lang/*.yml` keys; new log messages in English;
  - no new static singletons; services flow through constructors / `PluginContext`;
  - don't add `if (service != null)` guards for services that are always injected — fix construction instead.
- Update `docs/*.md`, `plugin-template` and `examples/` when an API or a documented behaviour changed.

## 4. Finish
- `/verify all` (full reactor) and, if runtime behaviour changed, a `--smoke` run.
- Update `docs/refactoring-plan.md`: tick the item, note what was deliberately left, add new items you discovered (don't fix drive-by issues silently — list them).
- Run the improvement loop of every skill you used (`/skill-maintenance`).
- Final message: what changed, what was verified and how, what is left, decisions the user still needs to make.

## Improvement loop (mandatory — see /skill-maintenance)
Fix wrong statements above; add dated **Learnings** (what made a chantier slower/riskier than expected, useful test patterns); prune stale ones.

## Learnings
- 2026-09-19: Initial version. Existing tests to copy patterns from: `AudioServiceImplTest` (Mockito on JDA `Guild`), `PluginConfigurationTest` (temp dirs), `FileDataStorageColdStartTest`.

## Known issues / open questions
- No test exercises `PluginManager` end-to-end (needs a fake plugin jar or an in-memory `Plugin` + descriptor). Building that fixture is probably the first task of the plugin-lifecycle chantier.
