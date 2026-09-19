---
name: skill-maintenance
description: The self-improvement loop for this project's skills in .claude/skills. Use at the end of any task where a project skill was loaded, when a skill said something wrong or outdated, when you learned something a future session should know, or when asked to create, audit or clean up a skill. Also documents how skills are structured in this repo.
argument-hint: "[skill-name] [--audit]"
---

# Skill maintenance loop

Skills are this project's institutional memory: each is the "expert" of one system. They stay useful only if every session that uses one **gives back** what it learned and **removes** what became false. This is part of the task, not optional polish.

## The loop (run for every skill used in this task)

1. **Verify** — every statement in the skill you relied on: did the code agree? If not, fix the statement now (or delete it). A wrong skill is worse than no skill.
2. **Capture** — under `## Learnings`, add dated one-liners (`- YYYY-MM-DD: ...`) for:
   - a fact you had to discover by reading code (with `path:line`),
   - a gotcha / bug / non-obvious ordering constraint,
   - a command, flag or test pattern that worked (or didn't),
   - a user decision that constrains future work (e.g. "API break accepted for X").
   Write for a reader with *no* conversation context.
3. **Prune** — remove learnings or known issues that are resolved (check the code / `git log -S`), duplicated or superseded. Fold recurring learnings into the body of the skill instead of keeping a long diary. Merge, don't append forever.
4. **Size** — keep `SKILL.md` under ~300 lines (hard limit 500). Move long reference material to a supporting file in the skill folder and link it; supporting files cost nothing until opened.
5. **Description** — if the skill failed to auto-trigger when it should have, or triggered wrongly, adjust `description` (use case first, trigger phrases, ≤ 1,536 chars).

Edit the SKILL.md in place; skill files are watched and reload live in the session.

## Creating a new skill

Create one when a subsystem, workflow or recurring question has no owner yet. Layout: `.claude/skills/<kebab-name>/SKILL.md` (+ optional `reference.md`, `scripts/`).

Frontmatter cheat-sheet (only what we use):
```yaml
---
name: <kebab>                      # defaults to the folder name
description: <what + when to use, trigger phrases first>
paths: fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/audio/**   # auto-load when working on these files
argument-hint: "[thing]"           # task skills
disable-model-invocation: true     # side-effecting tasks only the user should start
user-invocable: false              # pure reference material, hidden from the / menu
allowed-tools: Bash(mvn *)         # pre-approved for that turn
context: fork / agent: Explore     # run in an isolated subagent (content must be self-contained)
---
```
Body conventions in this repo:
- **Reference skills** (`fluxcord-*`): map of the system (files, flow, invariants) → gotchas → how to test → *Improvement loop* → `## Learnings` → `## Known issues / open questions`.
- **Task skills** (`verify`, `refactor-module`, ...): numbered protocol, `$ARGUMENTS`, explicit "ask the user" points.
- Facts come from the code, with paths. No aspirational "how it should work" unless labelled as a target.
- Dynamic context (`!` + backticked command) only for cheap commands (< 2 s); it runs on every invocation.

## Audit mode (`--audit`, or when asked to "clean up the skills")

For each `.claude/skills/*/SKILL.md`:
- `git log --since=<date of the last learning> --stat -- <paths the skill describes>` to spot changed files;
- spot-check 3-5 `path:line` references still exist (`grep -n`);
- entries older than ~3 months in *Learnings*: fold into the body or delete;
- check that no two skills contradict each other (e.g. lifecycle in `fluxcord-plugin-system` vs `fluxcord-plugin-dev`).
Report what changed.

## Learnings
- 2026-09-19: Skills were created in bulk from a first code audit; expect inaccuracies — the first uses of each skill should be aggressive on step 1 (verify).

## Known issues / open questions
- Decide whether learnings should be capped per skill (e.g. 20 lines) with overflow going to `reference.md`.
