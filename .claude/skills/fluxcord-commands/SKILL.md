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
| `core/command/SimpleCommandService.java` (~840 lines) | the engine: registry, parsers list, cooldown map, metrics, JDA sync (`synchronizeCommands` → global + per-guild `updateCommands`), `enable()`/`disable()`, `processCommand(jdaEvent)`, `executeCommand(cmd, ctx)`; also builds JDA `CommandData` (`createCommandData`, `buildOptionData`, `buildSubcommandData`). |
| `core/command/SimpleCommandRegistry.java` | flat maps name→command, alias→command, plugin→commands. Global namespace: two plugins registering `play` collide (warning, second one ignored). |
| `core/command/SimpleCommand.java`, `SimpleCommandBuilder.java`, `SimpleCommandContext.java`, `option/SimpleCommandOption.java` | implementations. |
| `core/command/CommandMessageBuilder.java` (~610 lines) | builds/sends replies for the three front-ends (content, embeds, components, ephemeral, deferral). |
| `core/command/parser/{Slash,Text,Console}CommandParser.java` + `CommandParser` | `canParse(event)` / `isCommandInvocation(event)` / `extractCommandName` / `parse(event, command)` → `CommandContext`. `ConsoleCommandParser` wraps a synthetic `parser/event/ConsoleCommandEvent`. |
| `core/command/listener/CommandListener.java` | JDA `ListenerAdapter`: `onSlashCommandInteraction`, `onMessageReceived` → `service.processCommand(event)`; `onCommandAutoCompleteInteraction` → `service.handleAutocomplete(event)`. |
| `core/command/system/{Help,Version,Shutdown}Command.java` | built-ins, toggled by `commands.system.*` in `config.yml`, registered once in `enable()`. |
| `core/console/ConsoleCommandService.java` | single thread reading `System.in`, forwards lines to the command service. |

## Flow
Boot: `Fluxcord` creates the service (needs `eventManager, languageManager, permissionManager, coreConfig, dataStorageManager, defaultPrefix`) → plugins register commands in `onEnable` → `commandService.setJDA(jda)` after connect → `enable()` registers `CommandListener`, system commands, and does one immediate `synchronizeCommands()` if JDA is `CONNECTED`. Registrations after boot go through a `Debouncer` (`scheduleDebouncedSync`, `SYNC_DELAY_MS`) so hot-reloaded plugins don't spam Discord.

Execution: `processCommand(event)` iterates parsers; first one that `canParse` + `isCommandInvocation` extracts the name, looks up name then alias, `parse`s a context, calls `executeCommand`. `executeCommand` checks: service enabled → command enabled → guildOnly → permission via `PermissionManager.hasPermission(userId, guildId, perm)` → cooldown → fires cancellable `CommandExecuteEvent` → `command.execute(ctx)` → cooldown applied → metrics → `CommandExecutedEvent`. Console invocations (`ctx.getUser() == null`) skip guild/permission/cooldown checks.

Replies: for slash commands, if the interaction is already acknowledged (deferred) only errors are auto-replied; success replies are the command's job. For text/console, errors are auto-replied. A command that neither replies nor defers within 3 s makes Discord show "The application did not respond".

Prefix: default from `commands.default-prefix`; per-guild override stored via `DataStorageManager` guild storage key `commands.prefix`.

## Gotchas
- Command names are global across plugins; the `group`/`category` fields don't namespace them.
- `i18n` keys for messages live in core `lang/*.yml` under `commands.messages.*` (`system_disabled`, `disabled`, `guild_only`, `permission_error`, `cooldown`, `execution_cancelled`, `execution_error`); the service uses `ctx.getLocale()`.
- `synchronizeCommands` silently no-ops when JDA isn't `CONNECTED` — the usual cause of "my command doesn't show up" after a reload.
- Guild-scoped commands (`guilds(...)`) are synced per guild; global ones take up to an hour to propagate on Discord's side — use guild scope while developing.
- `OptionType2` ↔ JDA `OptionType` conversion happens in `SimpleCommandService.convertOptionType`; attachment options go through `toFileType`.

## Testing
- No tests exist for this package. Good first targets: `SimpleCommandRegistry` (pure), `TextCommandParser` (needs a mocked `MessageReceivedEvent`), `executeCommand` gating logic with a fake `Command` and mocked `PermissionManager`.
- Smoke: register a guild-scoped test command in an example plugin and check the sync log + Discord UI.

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit; option-builder method list above is partial — read `CommandBuilder.java` for the full set before documenting an option type.
- 2026-09-20: `SimpleCommandRegistryTest` (11): alias removal was not owner-checked (fixed). C3 done: `AutocompleteProvider<T>` / `AutocompleteContext` in api (`CommandOption.getAutocompleteProvider()` now returns the provider type; the `Function<String,…>` builder overload is a default method adapting it), `SimpleCommandService.suggest(...)` is package-private for tests, `handleAutocomplete` caps at 25 and answers empty on provider exceptions. Console parser resolves `USER` options from a numeric ID via `jda.getUserById` then `retrieveUserById().complete()`. System commands `perm` (operators only) and `shutdown` check `PermissionManager.isOperator` in their executor instead of `.permission(...)` (system commands have no plugin to register a permission with).

## Known issues / open questions
- C1 (plan): `SimpleCommandService` mixes 5 concerns (registry façade, execution pipeline, JDA data mapping, sync/debounce, metrics/cooldown). Split.
- C2: `processCommand` is a nested loop with try/catch per parser; a `CommandParseException` from the right parser makes it fall through to the next parser instead of reporting a usage error.
- C3 (rename `OptionType2`): still open, API break — ask the user.
- Global command namespace collision across plugins: warn only, or prefix with plugin id? Decide with the user.
