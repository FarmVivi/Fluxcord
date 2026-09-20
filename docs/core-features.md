# Core Features Matrix - Fluxcord

This document provides a comprehensive overview of all features and APIs exposed by the Fluxcord framework to plugins.

## Feature Matrix

| Feature                         | Description                                              | Entry Points                                                 | How to Use                                                | Configuration                              | Example Plugin                                                   |
|---------------------------------|----------------------------------------------------------|--------------------------------------------------------------|-----------------------------------------------------------|--------------------------------------------|------------------------------------------------------------------|
| **Plugin System**               | Core plugin lifecycle and management                     | `AbstractPlugin`, `PluginContext`, `PluginManager`           | Extend `AbstractPlugin`, implement lifecycle methods      | `plugin.yml` metadata                      | [Example Template](../plugin-template/)                          |
| **Command System**              | Slash commands and text commands with permissions        | `CommandService`, `CommandBuilder`, `@Command`               | Register commands via `CommandService` or builder pattern | Command metadata, cooldowns, permissions   | [Example Commands](../examples/plugins/plugin-example-commands/) |
| **Event System**                | Fluxcord events (`@EventHandler`, priorities) and Discord events (JDA listeners) | `EventManager`, `@EventHandler`, `addDiscordListeners`      | `@EventHandler` for Fluxcord events, `ListenerAdapter` + `addDiscordListeners` for Discord events | Event priorities, async handling           | [Example Events](#)                                              |
| **Permission System**           | Role-based permissions with plugin namespacing           | `PermissionManager`, `Permission`, `PluginPermissionAdapter` | Register permissions, check with `hasPermission()`        | Permission defaults, role mappings         | [Example Permissions](#)                                         |
| **Configuration System**        | YAML-based plugin configuration with auto-loading        | `Configuration`, file-based configs                          | Access via `getConfiguration()`, automatic loading        | `config.yml` in plugin JAR and data folder | [Example Config](#)                                              |
| **Internationalization (i18n)** | Multi-language support with namespace isolation          | `LanguageManager`, `PluginLanguageAdapter`                   | Register namespace, load language files                   | Language files in `lang/` directory        | [Example i18n](#)                                                |
| **Data Storage**                | Persistent data storage with scoping (global/user/guild) | `DataStorageManager`, `PluginDataStorageAdapter`             | Use scoped storage methods (`getGlobalStorage()`, etc.)   | Storage backend configuration              | [Example Storage](#)                                             |
| **Binary Storage**              | Large file storage with multiple backends (File/S3)      | `BinaryStorageManager`, `PluginBinaryStorageAdapter`         | Store/retrieve files with automatic namespacing           | Storage backend selection                  | [Example Files](#)                                               |
| **Audio System**                | Advanced audio processing with mixing and priorities     | `AudioService`, `AudioSendHandler`, `AudioReceiveHandler`    | Register audio handlers with volume/priority              | Audio priorities, volume control           | [Example Audio](../examples/plugins/plugin-example-audio/)       |
| **Discord Integration**         | Full Discord API access through JDA abstraction          | `DiscordAPI`, `JDA` instance                                 | Access Discord entities and APIs                          | Bot token, intents, activity               | [Example Discord](#)                                             |
| **Logging System**              | Plugin-specific logging with configurable levels         | Plugin `Logger` instance                                     | Use provided logger in `AbstractPlugin`                   | Log levels, file output                    | Built into template                                              |
| **Plugin Communication**        | Inter-plugin dependency resolution and access            | `PluginLoader`, `PluginManager`                              | Declare dependencies, access other plugins                | `plugin.yml` dependencies                  | [Example Deps](#)                                                |

## Detailed Feature Documentation

### 1. Plugin System

**Description**: Core lifecycle management for plugins with dependency resolution.

**Entry Points**:

- `plugin.fr.farmvivi.fluxcord.api.AbstractPlugin` - Base plugin class
- `plugin.fr.farmvivi.fluxcord.api.PluginContext` - Access to core services
- `fr.farmvivi.fluxcord.api.plugin.PluginManager` - Plugin management

**How to Use**:

```java
public class MyPlugin extends AbstractPlugin {
    @Override
    public void onEnable() {
        logger.info("Plugin enabled!");
        // Initialize plugin functionality
    }
    
    @Override
    public void onDisable() {
        // Cleanup resources
    }
}
```

**Configuration**:

- `plugin.yml` - Plugin metadata (name, version, dependencies)
- Automatic data folder creation
- Lifecycle state management

**Prerequisites**: None

---

### 2. Command System

**Description**: Unified slash command and text command system with permissions and cooldowns.

**Entry Points**:

- `command.fr.farmvivi.fluxcord.api.CommandService` - Command registration and management
- `command.fr.farmvivi.fluxcord.api.CommandBuilder` - Fluent command creation
- `command.fr.farmvivi.fluxcord.api.CommandContext` - Command execution context

**How to Use**:

```java
// Using CommandBuilder
commandService.registerCommand(this, builder -> {
    builder.name("ping")
           .description("Ping the bot")
           .executor((context, cmd) -> {
               context.reply("Pong!");
               return CommandResult.success();
           });
});

// Raw interaction events, if ever needed: a JDA listener (see Event System)
addDiscordListeners(new ListenerAdapter() {
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) { /* ... */ }
});
```

**Configuration**:

- Command prefixes (global and per-guild)
- Cooldowns and rate limiting
- Permission requirements
- Command categories

**Prerequisites**: None (automatically enabled)

---

### 3. Event System

**Description**: Comprehensive event handling for Discord events and plugin events with priority-based execution.

**Entry Points**:

- `event.fr.farmvivi.fluxcord.api.EventManager` - Event registration and firing
- `event.fr.farmvivi.fluxcord.api.EventHandler` - Annotation for event handlers
- `event.fr.farmvivi.fluxcord.api.EventPriority` - Priority levels

**How to Use**:

```java
// Discord events: JDA ListenerAdapter, registered through the plugin (auto-removed on disable)
addDiscordListeners(new ListenerAdapter() {
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        logger.info("Message received: {}", event.getMessage().getContentRaw());
    }
});

// Fluxcord events: @EventHandler methods, registered with eventManager.registerListener(listener, this)
@EventHandler
public void onPluginEvent(PluginEnableEvent event) {
    // Handle plugin lifecycle events
}
```

**Configuration**:

- Event priorities (LOW, NORMAL, HIGH, HIGHEST)
- Async event handling
- Event cancellation support

**Prerequisites**: Register listener with `eventManager.registerListener(this, plugin)`

---

### 4. Permission System

**Description**: Flexible role-based permissions with plugin-specific namespacing.

**Entry Points**:

- `permissions.fr.farmvivi.fluxcord.api.PermissionManager` - Global permission management
- `permissions.fr.farmvivi.fluxcord.api.PluginPermissionAdapter` - Plugin-scoped permissions
- `permissions.fr.farmvivi.fluxcord.api.Permission` - Permission definition

**How to Use**:

```java
// Register permissions
getPluginPermissionManager().registerPermission(new SimplePermission(
    "myplugin.admin",
    "Administrative access to MyPlugin",
    PermissionDefault.OP
));

// Check permissions
if (getPluginPermissionManager().hasPermission(userId, "myplugin.admin")) {
    // User has permission
}
```

**Configuration**:

- Permission defaults (TRUE, FALSE, OP, NOT_OP)
- Guild-specific permissions
- User and role-based assignments

**Prerequisites**: Permissions must be registered in `onEnable()`

---

### 5. Configuration System

**Description**: YAML-based configuration with automatic loading and environment variable support.

**Entry Points**:

- `config.fr.farmvivi.fluxcord.api.Configuration` - Configuration interface
- Plugin `config.yml` files

**How to Use**:

```java
// Access configuration
Configuration config = getConfiguration();
String value = config.getString("my.setting", "default");
int number = config.getInt("my.number", 42);
boolean flag = config.getBoolean("enabled", true);

// Modify and save
config.set("my.setting", "new value");
config.save();
```

**Configuration**:

- Default config copying from plugin JAR
- Environment variable substitution
- Automatic version migration
- Per-guild configuration overrides

**Prerequisites**: Include `config.yml` in plugin resources

---

### 6. Internationalization (i18n)

**Description**: Multi-language support with namespace isolation and placeholder replacement.

**Entry Points**:

- `language.fr.farmvivi.fluxcord.api.LanguageManager` - Global language management
- `language.fr.farmvivi.fluxcord.api.PluginLanguageAdapter` - Plugin-scoped translations

**How to Use**:

```java
// Get translations
String message = getPluginLanguageManager().getString("welcome", "Hello {0}!", username);
String localized = getPluginLanguageManager().getString(Locale.FRENCH, "goodbye");
```

**Configuration**:

- Language files in `lang/` directory (`en-US.yml`, `fr-FR.yml`)
- Namespace-based key isolation
- Fallback to default language
- Placeholder support with `{0}`, `{1}`, etc.

**Prerequisites**: Language files in plugin resources, namespace registration

---

### 7. Data Storage

**Description**: Persistent data storage with automatic scoping by global, user, guild, and user-guild.

**Entry Points**:

- `fr.farmvivi.fluxcord.api.storage.DataStorageManager` - Storage management (interface; the core implements it)
- `fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter` - Plugin-scoped storage (`getPluginDataStorage()`)
- `fr.farmvivi.fluxcord.api.storage.ScopedStorage` - the view every `get*Storage(...)` returns: one scope, plus a key prefix (`<pluginId>.`) when obtained through the plugin adapter

**How to Use**:

```java
// Different storage scopes
getPluginDataStorage().getGlobalStorage().set("server.uptime", System.currentTimeMillis());
getPluginDataStorage().getUserStorage(userId).set("preferences.theme", "dark");
getPluginDataStorage().getGuildStorage(guildId).set("config.prefix", "!");
getPluginDataStorage().getUserGuildStorage(userId, guildId).set("xp", 1500);

// Keys are stored as "<pluginId>.<key>" inside the scope, so plugins never collide with each other or with
// core keys (e.g. "commands.prefix"); getKeys()/getAll()/clear() only see the plugin's own keys.

// Save changes
getPluginDataStorage().saveAll();
```

**Configuration**:

- Storage backend selection (File, Database, etc.)
- Automatic namespacing by plugin
- Hierarchical key structure

**Prerequisites**: None (automatic initialization)

---

### 8. Binary Storage

**Description**: Large file and binary data storage with multiple backend support.

**Entry Points**:

- `fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager` - Binary storage management (interface; the core implements it)
- `fr.farmvivi.fluxcord.api.storage.binary.PluginBinaryStorageAdapter` - Plugin-scoped binary storage (`getPluginBinaryStorage()`)
- `fr.farmvivi.fluxcord.api.storage.binary.ScopedBinaryStorage` - the view every `get*Storage(...)` returns; plugin files live under `<pluginId>/`

**How to Use**:

```java
// Store and retrieve files
BinaryStorageKey key = BinaryStorageKey.of("avatars", userId + ".png");
getPluginBinaryStorage().storeFile(key, imageBytes);
byte[] retrieved = getPluginBinaryStorage().getFile(key);

// File operations
boolean exists = getPluginBinaryStorage().fileExists(key);
getPluginBinaryStorage().deleteFile(key);
```

**Configuration**:

- Backend selection (File system, S3, etc.)
- Automatic plugin namespacing
- Directory structure management

**Prerequisites**: Configure storage backend in core config

---

### 9. Audio System

**Description**: Advanced audio processing with mixing, priorities, and volume control.

**Entry Points**:

- `audio.fr.farmvivi.fluxcord.api.AudioService` - Audio service management
- `net.dv8tion.jda.api.audio.AudioSendHandler` - Send audio to Discord
- `net.dv8tion.jda.api.audio.AudioReceiveHandler` - Receive audio from Discord

**How to Use**:

```java
// Register audio handler
audioService.registerSendHandler(guild, this, audioHandler, 
                                 AudioService.DEFAULT_VOLUME, 
                                 AudioService.DEFAULT_PRIORITY);

// Control volume and priority
audioService.setVolume(guild, this, 75);

// Handle received audio
audioService.registerReceiveHandler(guild, this, receiveHandler);
```

**Configuration**:

- Volume levels (0-100)
- Priority levels (0-100) with mixing
- Priority thresholds for automatic ducking

**Prerequisites**: Audio intents enabled, voice channel access

---

### 10. Discord Integration

**Description**: Full Discord API access through JDA with presence and connection management.

**Entry Points**:

- `discord.fr.farmvivi.fluxcord.api.DiscordAPI` - Discord API wrapper
- `net.dv8tion.jda.api.JDA` - Direct JDA access

**How to Use**:

```java
// Access Discord API
JDA jda = discordAPI.getJDA();
Guild guild = jda.getGuildById("123456789");

// Manage bot presence
discordAPI.setDefaultPresence(Activity.playing("My Game"), OnlineStatus.ONLINE);

// Connection management
discordAPI.connect().thenRun(() -> logger.info("Connected!"));
```

**Configuration**:

- Bot token and intents
- Startup/default/shutdown presence states
- Connection retry settings

**Prerequisites**: Valid Discord bot token

---

### 11. Logging System

**Description**: Plugin-specific logging with configurable levels and output destinations.

**Entry Points**:

- Plugin `Logger` instance provided by framework
- SLF4J logging API

**How to Use**:

```java
// Use provided logger
logger.info("Plugin operation completed");
logger.warn("Potential issue detected: {}", issue);
logger.error("Error processing request", exception);
logger.debug("Debug information: {}", debugData);
```

**Configuration**:

- Log levels per plugin
- Console and file output
- Log rotation and retention

**Prerequisites**: None (automatically provided)

---

### 12. Plugin Communication

**Description**: Inter-plugin dependency resolution and communication.

**Entry Points**:

- `plugin.fr.farmvivi.fluxcord.api.PluginLoader` - Access other plugins
- `plugin.yml` dependency declarations

**How to Use**:

```java
// In plugin.yml
dependencies: ["OtherPlugin"]
soft-dependencies: ["OptionalPlugin"]

// Access other plugins
Plugin otherPlugin = getContext().getPluginLoader().getPlugin("OtherPlugin");
if (otherPlugin != null && otherPlugin.isEnabled()) {
    // Interact with other plugin
}
```

**Configuration**:

- Hard dependencies (required)
- Soft dependencies (optional)
- Load order management

**Prerequisites**: Target plugins must be installed and compatible

---

## Feature Status Summary

### ✅ Fully Implemented Features

- Plugin System (lifecycle, context, management)
- Command System (slash and text commands)
- Event System (Discord and plugin events)
- Permission System (role-based with namespacing)
- Configuration System (YAML with auto-loading)
- Internationalization (multi-language support)
- Data Storage (persistent with scoping)
- Binary Storage (file storage with backends)
- Audio System (advanced mixing and priorities)
- Discord Integration (full JDA access)
- Logging System (plugin-specific logging)
- Plugin Communication (dependency resolution)

### ❌ Not Implemented Features

Based on code analysis, the following common bot framework features are **not present**:

- **Scheduler/Cron System**: No built-in task scheduling or cron job functionality
- **HTTP Client**: No dedicated HTTP/REST client utilities
- **Caching System**: No built-in caching layer
- **Metrics/Telemetry**: No metrics collection or monitoring APIs
- **Database Abstraction**: Only basic storage, no ORM or query builder
- **Web Dashboard**: No web interface for administration
- **Rate Limiting (Advanced)**: Only basic command cooldowns
- **Message Queuing**: No async message queue system

### Plugin Development Recommendations

1. **Essential Features**: All plugins should use Plugin System, Configuration, Logging, and Permissions
2. **Interactive Features**: Use Command System and Event System for user interaction
3. **Data Persistence**: Use Data Storage for settings, Binary Storage for files
4. **Multi-language**: Implement i18n for broader user base
5. **Audio Plugins**: Use Audio System for voice channel functionality
6. **Advanced Features**: Access Discord API directly for specialized needs

### Cross-Reference with Examples

| Feature          | Example Plugin   | Location                                                                                  |
|------------------|------------------|-------------------------------------------------------------------------------------------|
| Audio System     | Audio Example    | [examples/plugins/plugin-example-audio/](../examples/plugins/plugin-example-audio/)       |
| Command System   | Example Commands | [examples/plugins/plugin-example-commands/](../examples/plugins/plugin-example-commands/) |
| Plugin Lifecycle | Template Plugin  | [plugin-template/](../plugin-template/)                                                   |
| All Features     | *To be created*  | [examples/plugins/](#)                                                                    |

---

*This documentation is automatically maintained and reflects the current state of the Fluxcord framework APIs.*