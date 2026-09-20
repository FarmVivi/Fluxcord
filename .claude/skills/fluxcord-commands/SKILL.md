---
name: fluxcord-commands
description: Expert on Fluxcord's unified command system - Command/CommandBuilder/CommandContext API, SimpleCommandService, registry, slash/text/console parsers, JDA slash-command sync, cooldowns, permissions check, system commands (help/version/shutdown), per-guild prefixes. Use when adding or changing commands, options/autocomplete, subcommands, replies/deferral, command sync problems ("command not showing in Discord"), prefix handling or the console.
paths:
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/command/**
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/console/**
  - fluxcord-api/src/main/java/fr/farmvivi/fluxcord/api/command/**
---

# Command system

One `Command` model serves three front-ends: Discord slash commands, prefixed text messages, and the console (stdin).

## Map
| File | Role |
|---|---|
| `api/command/Command.java`, `CommandBuilder.java`, `CommandContext.java`, `CommandResult.java`, `CommandService.java`, `CommandRegistry.java` | public contract. `CommandBuilder` has `name/description/category/group/permission/translationKey/alias(es)/guildOnly/guilds/enabled/cooldown`, typed options (`stringOption`, `integerOption` with choices/validator/autocomplete/min-max, `booleanOption`, `userOption`, `channelOption`, `roleOption`, ...), subcommands, and `executor`. |
| `api/command/option/OptionType2.java` | enum mirroring JDA `OptionType` (the `2` suffix is a naming accident from the generation; plan item C3 renames it). |
| `api/command/PluginCommandAdapter.java` | per-plugin façade (`registerCommand`, `unregisterAll`, `getPrefix(guildId)`, sync helpers). |
| `core/command/SimpleCommandService.java` (~600 lines) | the façade: registry, parsers list, prefixes, JDA sync (`synchronizeCommands` → global + per-guild `updateCommands`, debounced after boot), `enable()`/`disable()`, `processCommand(jdaEvent)` → `dispatch`, autocomplete, system commands. |
| `core/command/CommandExecutor.java` | the execution pipeline: gating (service enabled → command enabled → guild-only → permission → cooldown → cancellable `CommandExecuteEvent`), execution, cooldown map, metrics, `CommandExecutedEvent`. Console (`ctx.getUser() == null`) skips guild/permission/cooldown. Refusals return a localised error result and are not counted. |
| `core/command/SlashCommandDataMapper.java` | command model → JDA `CommandData` (lower-cased names, bounds/choices/autocomplete/file types on top-level **and** subcommand options, `DefaultMemberPermissions.DISABLED` when the command has a permission, contexts from `guildOnly`). |
| `core/command/SimpleCommandRegistry.java` | flat maps name→command, alias→command, plugin→commands. Global namespace: two plugins registering `play` collide (warning, second one ignored). |
| `core/command/SimpleCommand.java`, `SimpleCommandBuilder.java`, `SimpleCommandContext.java`, `option/SimpleCommandOption.java` | implementations. |
| `core/command/CommandMessageBuilder.java` (~610 lines) | builds/sends replies for the three front-ends (content, embeds, components, ephemeral, deferral). |
| `core/command/parser/{Slash,Text,Console}CommandParser.java` + `CommandParser` | `canParse(event)` / `isCommandInvocation(event)` / `extractCommandName` / `parse(event, command)` → `CommandContext`. `ConsoleCommandParser` wraps a synthetic `parser/event/ConsoleCommandEvent`. |
| `core/command/listener/CommandListener.java` | JDA `ListenerAdapter`: `onSlashCommandInteraction`, `onMessageReceived` → `service.processCommand(event)`; `onCommandAutoCompleteInteraction` → `service.handleAutocomplete(event)`. |
| `core/command/system/{Help,Version,Shutdown}Command.java` | built-ins, toggled by `commands.system.*` in `config.yml`, registered once in `enable()`. |
| `core/console/ConsoleCommandService.java` | single thread reading `System.in`, forwards lines to the command service. |

## Flow
Boot: `Fluxcord` creates the service (needs `eventManager, languageManager, permissionManager, coreConfig, dataStorageManager, defaultPrefix`) → plugins register commands in `onEnable` → `commandService.setJDA(jda)` after connect → `enable()` registers `CommandListener`, system commands, and does one immediate `synchronizeCommands()` if JDA is `CONNECTED`. Registrations after boot go through a `Debouncer` (`scheduleDebouncedSync`, `SYNC_DELAY_MS`) so hot-reloaded plugins don't spam Discord.

Execution: `processCommand(event)` gives the event to the first parser whose `canParse` + `isCommandInvocation` match (`dispatch`): extract name → registry lookup (name, then alias; unknown → debug log, silent) → `parse` (a `CommandParseException` = missing/invalid option → ephemeral `commands.messages.parse_error` reply through `CommandMessageBuilder` directly, no context exists yet) → `CommandExecutor.execute`.

Replies: a refused or failed execution is answered with `context.replyError(result.getErrorMessage())` unless the command already replied itself (`CommandContext.hasReplied()`, e.g. its own usage message) — first reply on a fresh interaction, edit of the "thinking…" placeholder when the command deferred, message/console line otherwise. Success replies are the command's job. A command that neither replies nor defers within 3 s makes Discord show "The application did not respond".

Prefix: default from `commands.default-prefix`; per-guild override stored via `DataStorageManager` guild storage key `commands.prefix`.

## Gotchas
- Command names are global across plugins; the `group`/`category` fields don't namespace them.
- `i18n` keys for messages live in core `lang/*.yml` under `commands.messages.*` (`system_disabled`, `disabled`, `guild_only`, `permission_error`, `cooldown`, `execution_cancelled`, `execution_error`); the service uses `ctx.getLocale()`.
- `synchronizeCommands` silently no-ops when JDA isn't `CONNECTED` — the usual cause of "my command doesn't show up" after a reload.
- Guild-scoped commands (`guilds(...)`) are synced per guild; global ones take up to an hour to propagate on Discord's side — use guild scope while developing.
- `OptionType2` ↔ JDA `OptionType` conversion happens in `SimpleCommandService.convertOptionType`; attachment options go through `toFileType`.

## Testing
- `SimpleCommandRegistryTest`, `CommandAutocompleteTest`, `CommandExecutionTest` (gating, cooldowns, events, metrics, and the reply contract of `processCommand` with mocked `SlashCommandInteractionEvent`s — errors are embeds: capture `reply("").addEmbeds(...)`), `SlashCommandDataMapperTest`. Missing: `TextCommandParser` (mention/role/channel parsing), `ConsoleCommandParser`, `CommandMessageBuilder`.
- Test setup: `jda.getStatus()` must **not** be `CONNECTED` in unit tests or `enable()` calls `jda.updateCommands()` (NPE on a plain mock); `LanguageManager.getString(locale, key, Object...)` is a varargs method — stub with `any(Object[].class)` and read `inv.getArguments()`.
- Smoke: register a guild-scoped test command in an example plugin and check the sync log + Discord UI.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit; option-builder method list above is partial — read `CommandBuilder.java` for the full set before documenting an option type.
- 2026-09-20: `SimpleCommandRegistryTest` (11): alias removal was not owner-checked (fixed). C3 done: `AutocompleteProvider<T>` / `AutocompleteContext` in api (`CommandOption.getAutocompleteProvider()` now returns the provider type; the `Function<String,…>` builder overload is a default method adapting it), `SimpleCommandService.suggest(...)` is package-private for tests, `handleAutocomplete` caps at 25 and answers empty on provider exceptions. Console parser resolves `USER` options from a numeric ID via `jda.getUserById` then `retrieveUserById().complete()`. System commands `perm` (operators only) and `shutdown` check `PermissionManager.isOperator` in their executor instead of `.permission(...)` (system commands have no plugin to register a permission with).

- 2026-09-20 (C1/C2): `CommandExecutor` + `SlashCommandDataMapper` extracted; `dispatch` replaces the nested parser loop. Two real bugs fixed: a slash command refused by permission/cooldown/guild-only got no reply at all (only deferred failures were answered), and subcommand options were synced as bare `OptionData` (no choices/bounds/autocomplete).

- 2026-09-20 (C6): subcommands are routed inside `parse` (`CommandParser.selectSubcommand`), so `context.getCommand()` is the subcommand and `dispatch` executes that. `SimpleCommand` is a record: the parent↔child cycle goes through the mutable `SimpleCommand.ParentLink`, set by `SimpleCommandBuilder.linkParents` after the parent is built (a `withEnabled` copy of the parent leaves children pointing at the original — equal data, fine). Permission/guild-only inherit from ancestors (`CommandExecutor.effectivePermission/isGuildOnly`); cooldowns keyed by `getFullName()`.

## Known issues / open questions
- C3 (rename `OptionType2`): still open, API break — ask the user.
- Global command namespace collision across plugins: warn only, or prefix with plugin id? Decide with the user.
