---
name: update-deps
description: Upgrade one or more Fluxcord dependencies (Maven library or plugin, JDK, Docker base image, GitHub Action) from an announced version, a pasted Renovate PR or dashboard entry, or a plain "do the bumps". Reads the release notes of every intermediate version, checks breaking changes and changed defaults against what the code really uses, bumps, adopts the new APIs, builds, tests and reports. Also owns the Renovate configuration (.github/renovate.json5). Improves itself on every run.
argument-hint: "[dependency, Renovate PR number, or empty for all]"
paths: pom.xml, **/pom.xml, Dockerfile*, .github/renovate.json5
---

# Upgrade a dependency (Fluxcord)

Target: **$ARGUMENTS** (empty: everything Renovate's dependency dashboard lists, plus a fresh check, see §0).

Goal: move a dependency **without silent regression**, and align the code with the new version rather
than merely make it compile.

- **A breaking change only counts if it touches what Fluxcord actually uses.** Most announced changes are
  irrelevant; the work is to prove it, not to assume it.
- **A changed default is more dangerous than a removed API.** A removed API fails the build; a changed
  default compiles, often passes the tests, and breaks in production (mariadb 4.0.0 turning TLS on is the
  example in this repo).

One dependency at a time, end to end. Commit or push only when Victor asks (CLAUDE.md).

## 0. Find what is really outdated

Renovate (`.github/renovate.json5`, Mend app installed on the repo since 2026-11-07) opens the PRs and keeps
the "Dependency dashboard (Renovate)" issue. **An open bot PR is not the truth**: it may propose an old
target, or a version already on `develop`. Settle it with a fresh check:

```bash
# Maven: root pom + modules (the youtube-source snapshot makes the versions plugin crash on music-plugin;
# that dependency is tracked by hand, see "Pending upgrades")
mvn -B org.codehaus.mojo:versions-maven-plugin:2.21.0:display-dependency-updates -DprocessDependencyManagement=true \
  | grep -E -- "->"
# the plugin-updates goal prints a "minimum Maven version" grid, not updates: ignore it
# GitHub Actions / upstream releases
gh release list -R <owner/repo> --limit 5
gh api repos/<owner>/<repo>/releases/latest --jq .tag_name
# Docker Hub tags (temurin, maven)
curl -s "https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags?page_size=100&name=25.0" | grep -oE '"name":"25[^"]*jre-noble"'
```

## 1. Map real usage (always first)

- Where the version is declared: almost always a property in the root `pom.xml` `<properties>` (versions
  are centralised in `<dependencyManagement>`; child poms carry none). Exceptions: music-plugin pins
  lavaplayer / youtube-source / LavaSrc and its own shade plugin; Docker tags live in the 4 `Dockerfile*`;
  the JDK is `java.version` in the pom + `java-version` in 6 workflows + the Docker tags.
- Every *twin* reference: `grep -rn "<old version>" --exclude-dir=target --exclude-dir=logs .` (README, docs,
  comments, Dockerfiles, workflows).
- What the code uses: `grep -rln "import <package>" --include=*.java`; direct vs transitive with
  `mvn dependency:tree -pl <module>`.
- Coupled dependencies (bump together): JDA + JDAVE; the jdave-* artifacts (one property); LavaSrc
  artifacts; JUnit artifacts; mockito-core + mockito-junit-jupiter; the JDK across pom/workflows/images.
- Plugin classloading: anything in `PluginClassLoader.CORE_PACKAGES` is parent-loaded, so a core upgrade of
  JDA / slf4j / snakeyaml / gson / HikariCP / AWS SDK changes what every plugin sees at runtime.

## 2. Read the release notes - mandatory before touching a file

- **Every intermediate version**, not only the target: release notes, changelog, migration guide.
- No notes published: read the compare (`gh api repos/<o>/<r>/compare/<old>...<new>`) and say so.
- Long changelogs truncate: fetch them whole and search locally (breaking, removed, deprecated, default,
  migration). The AWS SDK publishes one JSON per version, which filters well:
  `curl -s https://raw.githubusercontent.com/aws/aws-sdk-java-v2/master/.changes/<v>.json`, then keep the
  `S3`, `Core`, `AWS SDK for Java v2`, HTTP-client entries.
- `gh release view <tag> -R <o>/<r> --json body --jq .body` reads one release.

## 3. Assess the impact (the core of the work)

Cross every point of the notes with §1, on these axes, and give an explicit verdict per point
("concerned / not concerned, because..."):

1. **Removed, renamed, deprecated APIs** we use.
2. **Environment requirements**: minimum JDK, Maven, glibc (JDAVE natives need glibc >= 2.38, hence the
   Ubuntu `noble` images), transitive libs.
3. **Changed defaults**: does our code set the value explicitly (no impact) or rely on the default
   (follow it knowingly, or pin the old value with a comment saying why)?
4. **Runtime behaviour** compilation cannot see: serialization, TLS, config keys, DB schema/SQL
   (private deployment constraints: CLAUDE.local.md if present), memory, threads, logs.
   JDA 6.7.0 was this kind: it compiled untouched and changed what a channel object can do.
5. **Transitive conflicts** (`mvn dependency:tree`, shade warnings about overlapping classes).

The changelog is not the truth: for any subtle point read the source or Javadoc of the target version.

## 4. Bump

Change the property (one place), plus the twins from §1, plus whatever the environment requirement forces
in the same batch. With a Renovate PR, either review and merge it, or bump on `develop` yourself and let
Renovate close its PR (it does so on its next run when the dependency is up to date).
**Never close a bot PR by hand to "clean up"**: a manually closed Dependabot PR made it skip that release
for good, and a closed Renovate PR does the same ("ignored" in the dashboard).

## 5. Adopt the new version

Replace deprecated APIs by their successors; adopt new APIs or options the notes recommend when they help
(performance, security, readability); use migration tooling when it exists, then reread the result. Check a
new pattern covers every case before applying it everywhere. Beyond ~15 call sites, script the change and
**assert every scripted replacement matched** (see the verify skill).

## 6. Verify

- `/verify`: a root pom change means the full `mvn -B -ntp clean package` (all modules, all tests).
  To verify only your change while the tree holds other WIP, build it in a clean worktree (verify skill).
- Zero new warnings (`-Xlint` deprecations, shade overlap warnings).
- A green suite proves nothing about a changed default: write or run a targeted test per §3 behaviour.
- Check the shaded jar really carries the new version:
  `unzip -p fluxcord-core/target/fluxcord-core-*-shaded.jar META-INF/maven/<group>/<artifact>/pom.properties`.
- Runtime-only libs (JDA, JDAVE, lavaplayer, storage drivers): smoke run (`/verify --smoke`).
- Docker base images: the `Docker Development Build` workflow on `develop` builds them; watch it with
  `gh run list --branch develop` after the push.

## 7. Report

Per dependency: before/after, what was **read** (notes, compare), impact points with verdicts, code changes,
what was verified and how, what remains to prove (smoke, pre-prod). Add a short dated entry to
`docs/refactoring-plan.md` (dependency rounds are logged there).

A bump that is impossible for now (measured blocker) is **not** hidden by disabling it in Renovate without
a trace: record it under "Pending upgrades" below (what, why, how to tell it is unblocked).

## 8. Improve this skill (last step, every run)

Add the traps met during the run (especially false leads and silent breakages) and the commands that
**really** worked; prune as much as you add (delete what proved wrong or stale, merge close traps into one
rule); keep it under ~300 lines, moving a tricky dependency's details into its own file in this folder.
Only document what was verified during the run. Say in the report whether the skill was updated.

## Renovate configuration (owned here)

- File: `.github/renovate.json5` (comments allowed - every non-obvious rule carries a `description`).
  Base branch `develop`, no automerge, `chore(deps):` commits, AWS SDK grouped weekly, LavaSrc / JDAVE
  grouped, JDK pinned to the 25 LTS (temurin + maven images + setup-java `java-jdk`), youtube-source
  disabled (pending below).
- Validate: `npx --yes --package renovate -- renovate-config-validator --strict` (repo root).
- **Dry-run locally before pushing a config change** - it is what found that setup-java's `java-version`
  is managed too (Renovate wanted Java 27 there):
  ```bash
  export RENOVATE_CONFIG_FILE="$PWD/.github/renovate.json5" GITHUB_COM_TOKEN=$(gh auth token) \
    LOG_LEVEL=debug LOG_FORMAT=json RENOVATE_PLATFORM=local RENOVATE_DRY_RUN=lookup
  npx --yes renovate > "$TEMP/renovate.jsonl" 2>&1
  # then read the "packageFiles with updates" record (deps[].updates[].newValue, deps[].skipReason)
  ```
  `--platform=local` only sees **committed** files: an untracked config is silently ignored ("No renovate
  config file found"), hence `RENOVATE_CONFIG_FILE`. Without `GITHUB_COM_TOKEN` every action is skipped
  as `github-token-required`. To test a rule on an old version, make a throwaway git repo in the scratchpad
  with a one-line Dockerfile/pom and run the same command there.
- Harmless dry-run noise: "Maven: error fetching versions ... not-found" (one per declared repository
  tried), Docker Hub 403 on deep pagination, "pom.xml contains windows line endings" (Windows checkout;
  the repo stores LF), "does not support local presets".

## Do not bump blindly

| Dependency | Why |
| --- | --- |
| JDK (pom `java.version`, workflows, temurin/maven images) | LTS only; JDAVE natives (FFM, glibc >= 2.38) must be proven to load on the new JDK; `<source>/<target>` instead of `<release>` in the root pom means a newer build JDK can link newer APIs unnoticed - fix that in the same change |
| JDA | behaviour changes hide in minor versions (6.7.0 channel obfuscation, mandatory 2026-11-16); couple with JDAVE |
| JDAVE | native binaries; smoke-test voice |
| mariadb-java-client 4.x | TLS on by default (`sslMode` documented in `config.yml`, `docs/configuration.md`) |
| lavaplayer / youtube-source / LavaSrc | shaded and relocated in music-plugin; YouTube breakage is usually not a version problem (LOGIN_REQUIRED -> OAuth/poToken) |
| maven image 3.10 | new minor of Maven itself: read its release notes like a library |

## Pending upgrades

- **youtube-source**: pinned to `2be8e542...-SNAPSHOT` (master, 2026-09-17) for an unreleased fix; Renovate
  cannot rank a release above that string, so it is disabled there. Unblocked when a release > 1.18.2
  exists: `gh release list -R lavalink-devs/youtube-source --limit 3`, then switch the two artifacts back to
  the release and remove the `dev.lavalink.youtube` rule. Checked 2026-11-07: still 1.18.2, pin = tip of master.
- **Next Java LTS**: Renovate is held on 25 on purpose (26/27 are non-LTS and short-lived). Move pom,
  workflows and images together, and widen the three `25` rules in `renovate.json5`.
- **slf4j 2.1.0**: only alphas so far (Renovate ignores unstable versions while we are on a stable one).

## Learnings

- 2026-11-07: Of seven open Dependabot PRs, four were already on `develop` and one proposed 2.57.8 while
  2.57.11 existed: always check §0 before trusting a bot PR.
- 2026-11-07: Dependabot's Maven job had failed daily since late October because its proxy refused
  non-allowlisted hosts (`egress not allowlisted maven.lavalink.dev` -> 403, reported as
  `private_source_authentication_failure`). Read the job log (`gh run view <id> --log | grep -i <host>`)
  before blaming the remote repo. Replaced by Renovate the same day.
