---
name: verify
description: Build, test and smoke-check Fluxcord after a change. Use after editing any Java/pom/resource file, before saying a task is done, when asked "does it compile/work", or when the Stop hook reports unverified edits. Picks the cheapest Maven command that actually proves the change.
argument-hint: "[module|all] [--smoke]"
allowed-tools: Bash(mvn *) Bash(java -jar *) Bash(./build.sh *) Bash(cat *) Bash(ls *) Bash(grep *) Bash(tail *) Bash(touch *)
---

# Verify a change (Fluxcord)

Rule from CLAUDE.md: **every source change is verified before the task is reported done**. A change that was not compiled and tested is not done. Report the real result, including failures.

## 1. Pick the scope

!`git status --short | head -30`

| Touched files | Minimum command |
|---|---|
| Only `fluxcord-api` | `mvn -q -pl fluxcord-core -am test` (reactor rebuilds api first) |
| `fluxcord-core` (main or test) | `mvn -q test -pl fluxcord-core` |
| One test class | `mvn -q test -pl fluxcord-core -Dtest=AudioMixerTest` (or `Class#method`) |
| A plugin (`plugins/*`, `examples/*`, `plugin-template`) | `mvn -q -pl <module-path> -am package -DskipTests` (e.g. `-pl plugins/music-plugin -am`) |
| Root `pom.xml`, shade config, resource filtering, anything cross-module, or before a commit | `mvn -q clean package` (full reactor, runs all tests) |

`-q` keeps output short; drop it when a failure needs the full log. Maven prints `BUILD SUCCESS` / `BUILD FAILURE` at the end — grep for it if output is long. Run in foreground; don't start background builds you then forget.

## 2. Read the result properly

- `COMPILATION ERROR` → fix the code, re-run. Never silence with `-Dmaven.test.skip`.
- `Tests run: N, Failures: F, Errors: E` → open `fluxcord-core/target/surefire-reports/<Test>.txt` for the stack trace.
- A test that already failed *before* your change: say so explicitly, don't hide it, don't "fix" it by deleting it without asking.
- Warnings about `Enable-Native-Access` / JDAVE natives on non-glibc hosts are expected; not a failure.

## 3. Smoke run (`--smoke`, or whenever runtime behaviour changed)

Compiling doesn't prove plugin loading, JDA wiring, command sync or storage. When the change touches boot, plugin lifecycle, commands, storage or audio:

1. Ensure `fluxcord-core/run/config.yml` exists with a real `discord.token` (git-ignored). If missing, **ask the user** — never invent a token.
2. Copy fresh plugin jars into `fluxcord-core/run/plugins/` if a plugin is involved (`plugins/music-plugin/target/*-shaded.jar` or the example jars).
3. Start `mvn -pl fluxcord-core exec:java` (uses `run/` + `logback-dev.xml`) **in the background**; watch the log for `Started in`, `Plugin fully enabled`, command sync messages and stack traces; then stop it and check the shutdown hook ran cleanly (`Goodbye!`).
4. Health check: `curl -s localhost:8081/readyz` (also `/healthz`, `/version`; port from `HEALTH_PORT`).
5. Report what you observed (quote the relevant log lines), not what you expected.

If a smoke run is impossible (no token, no network), state it plainly in the final message.

## 4. Docs-only change

If nothing under `src/`, `pom.xml` or resources changed, no build is needed: say so and run `touch .claude/state/last-build` so the Stop hook lets the turn end.

## Improvement loop (mandatory — see /skill-maintenance)
Before ending a task where this skill was used: fix anything above that turned out wrong; add a dated one-liner to **Learnings** for anything that cost time (measured timings, flaky tests, needed flags); delete entries that no longer hold. Keep this file < 500 lines.

## Learnings
- 2026-09-19: Initial version; commands taken from CLAUDE.md / root pom (`defaultGoal` = `clean package`).
- 2026-09-20: Measured on the Windows workstation: full `mvn -B -ntp verify` ≈ 35-40 s warm; a single core test class ≈ 15 s (mostly Maven startup). `-ntp` silences the transfer-progress noise.
- 2026-09-20: IntelliJ holds locks on `target/` while it re-indexes/compiles (e.g. right after a `git stash`), making `mvn clean` fail with "Failed to clean project". Wait a few seconds or pass `-Dmaven.clean.failOnError=false`; never kill the IDE java processes.
- 2026-09-20: A test that passes alone but fails in the full suite is usually a race in production code, not test order — treat it as a bug (see `fluxcord-storage` learnings for the `FileDataStorage.close()` case).
- 2026-09-20: CI now runs `mvn -B -ntp verify` (`.github/workflows/ci.yml`) on push/PR; JaCoCo reports land in `**/target/site/jacoco/` (core coverage 9 % at start).

## Known issues / open questions
- Confirm `mvn -q -pl fluxcord-core -am test` picks up api changes without a prior `install` (it should, same reactor).
