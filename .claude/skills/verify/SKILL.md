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

0. **Private pre-check** (see `CLAUDE.local.md` if present): the local token may be shared with another running instance of the bot; make sure no other instance runs before starting one here.
1. Ensure `fluxcord-core/run/config.yml` exists with a real `discord.token` (git-ignored). Check presence without printing it (`grep -c "token:"`, or the token length via `awk`). If missing, **ask the user** — never invent or print a token.
2. Build what the run needs: `mvn -B -ntp -q -pl fluxcord-core -am package -DskipTests` (+ the plugin module if a plugin changed), then copy fresh plugin jars into `fluxcord-core/run/plugins/` (`plugins/music-plugin/target/*-shaded.jar` or the example jars).
3. Run **from `fluxcord-core/run/`**, with `shutdown` sent on stdin after the boot so the clean shutdown path is exercised too (Windows: `Stop-Process`/`taskkill` kill without running the JVM shutdown hook, so never judge the shutdown from them):
   ```bash
   cd fluxcord-core/run && mkdir -p logs
   ( (sleep 40; echo shutdown) | java -Dlogback.configurationFile=logback-dev.xml --enable-native-access=ALL-UNNAMED \
       -jar ../target/fluxcord-core-*-shaded.jar > logs/smoke.log 2>&1 )
   ```
   (`mvn -pl fluxcord-core exec:exec` starts the same thing in the foreground with the right working directory, but its stdin is not forwarded — use it for interactive runs, the jar for scripted ones.)
4. While it runs (in another shell, or by shortening the sleep loop): `curl -s localhost:8081/healthz`, `/readyz` (ok once fully started), `/version`.
5. Read `logs/smoke.log` ignoring `WARNING:` (JDK) and `DEBUG` lines. Expected sequence: `Loaded plugin: <id>` → `Synchronizing N global commands` → `Command service enabled` → `Plugin fully enabled: <id>` → `Started in X.Xs!` → `Global commands synchronized` → (after `shutdown`) `Shutting down...` → `Plugin fully disabled` → `Command service disabled` → `Goodbye!`. Any `ERROR`/stack trace or a missing `Goodbye!` is a finding. A missing `Started in` after ~30 s usually means a wrong token or a privileged intent not enabled in the developer portal.
6. Report what you observed (quote the relevant log lines and timings), not what you expected. Leave `run/` as you found it except `logs/`.

If a smoke run is impossible (no token, no network, another instance already running), state it plainly in the final message.

## 4. Docs-only change

If nothing under `src/`, `pom.xml` or resources changed, no build is needed: say so and run `touch .claude/state/last-build` so the Stop hook lets the turn end.

## Improvement loop (mandatory — see /skill-maintenance)
Before ending a task where this skill was used: fix anything above that turned out wrong; add a dated one-liner to **Learnings** for anything that cost time (measured timings, flaky tests, needed flags); delete entries that no longer hold. Keep this file < 500 lines.

## Learnings
- 2026-09-19: Initial version; commands taken from CLAUDE.md / root pom (`defaultGoal` = `clean package`).
- 2026-09-20: Measured on the Windows workstation: full `mvn -B -ntp verify` ≈ 35-40 s warm; a single core test class ≈ 15 s (mostly Maven startup). `-ntp` silences the transfer-progress noise.
- 2026-09-20: IntelliJ holds locks on `target/` while it re-indexes/compiles (e.g. right after a `git stash`), making `mvn clean` fail with "Failed to clean project". Wait a few seconds or pass `-Dmaven.clean.failOnError=false`; never kill the IDE java processes.
- 2026-09-20: A test that passes alone but fails in the full suite is usually a race in production code, not test order — treat it as a bug (see `fluxcord-storage` learnings for the `FileDataStorage.close()` case).
- 2026-09-20: `exec:java` ignored `<workingDirectory>` (in-process goal): the bot started at the repo root and created `config.yml` + `logs/` there. Switched the pom to `exec:exec`; if stray `config.yml`/`logs/` appear at the root, that is the symptom. First measured smoke run: boot 4.5-5 s, 15 global commands synced, clean shutdown < 1 s.
- 2026-09-20: CI now runs `mvn -B -ntp verify` (`.github/workflows/ci.yml`) on push/PR; JaCoCo reports land in `**/target/site/jacoco/` (core coverage 9 % at start).
- 2026-09-20: The git remote is named `github` (not `origin`): `git push github develop`. `gh run watch <id> --exit-status` is a convenient way to wait for CI after a push.

## Known issues / open questions
- Confirm `mvn -q -pl fluxcord-core -am test` picks up api changes without a prior `install` (it should, same reactor).
