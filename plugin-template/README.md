# Plugin Template

A comprehensive template for creating Fluxcord plugins with best practices, examples, and complete feature coverage.

⚠️ **Quick Start**: All example features are **disabled by default**. Enable them in `config.yml` to explore
functionality or keep them disabled for a clean starting point.

## Features

### 🎯 Core Functionality

- **Plugin Lifecycle**: Complete lifecycle management with all phases (load, pre-enable, enable, post-enable, disable)
- **Configuration System**: YAML-based configuration with feature toggles and environment variable support
- **Command System**: Example slash command registration with permissions and i18n
- **Event System**: Discord and plugin event handling with priority management
- **Permission System**: Role-based permissions with plugin namespacing
- **Internationalization**: Multi-language support (English and French examples)
- **Data Storage**: Persistent data storage with user/guild/global scoping
- **Service Architecture**: Organized code structure with service classes

### 🛠️ Development Tools

- **Unit Testing**: Complete test examples with JUnit 5 and Mockito
- **Maven Integration**: Proper build configuration with test support
- **Documentation**: Comprehensive comments and usage examples
- **Debugging**: Configurable debug logging and extended debug mode

### 📚 Educational Examples

- **Commands**: Example command with permission checks and i18n
- **Events**: Discord message event handling with configuration checks
- **Storage**: User preference management and statistics tracking
- **Services**: Data service example with error handling

## Quick Start

### 1. Copy and Rename

```bash
cp -r plugin-template my-awesome-plugin
cd my-awesome-plugin
```

### 2. Update Project Details

**Edit `pom.xml`:**

```xml
<artifactId>my-awesome-plugin</artifactId>
<name>My Awesome Plugin</name>
<description>Description of what your plugin does</description>
```

**Edit `plugin.yml`:**

```yaml
name: MyAwesomePlugin
version: 1.0.0
main: com.yourcompany.plugin.MyAwesomePlugin
description: Your plugin description
author: YourName
```

### 3. Enable Example Features (Optional)

**Edit `config.yml`:**

```yaml
features:
  example_commands: true    # Enable to see command examples
  example_events: true      # Enable to see event examples  
  example_storage: true     # Enable to see storage examples
```

### 4. Rename Package and Class

1. Rename package: `com.example.plugin` → `com.yourcompany.plugin`
2. Rename class: `TemplatePlugin.java` → `MyAwesomePlugin.java`
3. Update imports in all files

### 5. Build and Test

```bash
mvn clean package
mvn test
```

## Template Structure

```
plugin-template/
├── pom.xml                               # Maven configuration with test dependencies
├── CHANGELOG.md                          # Version history template
├── README.md                             # This file
├── src/main/
│   ├── java/com/example/plugin/
│   │   ├── TemplatePlugin.java           # Main plugin class with lifecycle examples
│   │   ├── commands/
│   │   │   └── ExampleCommand.java       # Command system examples
│   │   ├── events/
│   │   │   └── ExampleEventListener.java # Event handling examples
│   │   └── services/
│   │       └── ExampleDataService.java   # Data service examples
│   └── resources/
│       ├── plugin.yml                    # Plugin metadata
│       ├── config.yml                    # Feature-rich configuration
│       └── lang/                         # Internationalization
│           ├── en-US.yml                 # English translations
│           └── fr-FR.yml                 # French translations
└── src/test/
    └── java/com/example/plugin/
        └── TemplatePluginTest.java       # Unit test examples
```

## Configuration Features

The template includes a comprehensive configuration system:

```yaml
# Feature toggles - disable examples you don't need
features:
  example_commands: false
  example_events: false
  example_storage: false
  respond_to_mentions: false

# Command system configuration
commands:
  enabled: true
  prefix: "!"
  cooldown: 3

# Permission system configuration
permissions:
  default_permission: true
  admin_nodes:
    - "templateplugin.admin"
    - "templateplugin.config"

# Storage configuration
storage:
  enabled: true
  format: "json"
  autosave_interval: 30

# Debug configuration
debug:
  log_messages: false
  extended_debug: false
```

## Example Code Highlights

### Command Example

```java
getCommands().registerCommand(builder -> {
    builder.name("template-example")
           .description(getLanguage().getString("commands.example"))
           .executor((context, cmd) -> exampleCommand.execute(context));
});
```

### Event Example

```java
// Discord events: ListenerAdapter override, registered with addDiscordListeners(listener).
// @EventHandler methods only receive Fluxcord events (PluginEnableEvent...).
@Override
public void onMessageReceived(MessageReceivedEvent event) {
    if (!exampleEventsEnabled || event.getAuthor().isBot()) {
        return;
    }
    // Handle event...
}
```

### Storage Example

```java
public void saveUserPreference(String userId, String key, Object value) {
    plugin.getStorage().getUserStorage(userId).set(key, value);
    plugin.getStorage().saveAll();
}
```

### Permission Example

```java
getPermissions().registerPermission(new SimplePermission(
    pluginPrefix("use"),
    "Allows usage of basic template features",
    PermissionDefault.TRUE
));
```

## Testing

The template includes comprehensive unit tests:

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=TemplatePluginTest

# Test with verbose output
mvn test -Dtest=TemplatePluginTest -DforkCount=1 -DreuseForks=false
```

**Test Features:**

- Plugin lifecycle testing
- Mock context setup
- Configuration testing
- Permission verification
- Service testing patterns

## Internationalization

Multi-language support with namespace isolation:

**English (`lang/en-US.yml`):**

```yaml
template:
  messages:
    welcome_user: "Welcome, {0}!"
    example_message: "Hello from Template Plugin!"
  errors:
    no_permission: "You don't have permission to use this command"
```

**French (`lang/fr-FR.yml`):**

```yaml
template:
  messages:
    welcome_user: "Bienvenue, {0} !"
    example_message: "Bonjour depuis le Plugin Template !"
  errors:
    no_permission: "Vous n'avez pas la permission d'utiliser cette commande"
```

**Usage:**

```java
String message = getLanguage().getString("messages.welcome_user", username);
```

## Dependencies

The template includes all necessary dependencies:

```xml
<!-- Core API -->
<dependency>
    <groupId>fr.farmvivi.fluxcord</groupId>
    <artifactId>fluxcord-api</artifactId>
</dependency>

<!-- Testing -->
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter-api</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

## Available Examples

- **[Audio Example](../examples/plugins/plugin-example-audio/)**: Audio processing with voice channels
- **[Commands Example](../examples/plugins/plugin-example-commands/)**: Command handling implementation
- **[Music Plugin](../plugins/music-plugin/)**: Advanced music bot (structure only)
- **[AI Audio Plugin](../plugins/ai-audio-plugin/)**: AI voice processing (structure only)

## Documentation

- **[Plugin Development Guide](../docs/plugin-development.md)**: Comprehensive development guide
- **[Template Quickstart](../docs/plugins/template-quickstart.md)**: Detailed step-by-step guide
- **[Core Features](../docs/core-features.md)**: Complete API reference
- **[Examples Overview](../docs/plugins/examples-overview.md)**: All available examples

## Best Practices Included

✅ **Modular Architecture**: Services, commands, and events in separate packages  
✅ **Configuration-Driven**: Feature toggles and extensive configuration options  
✅ **Error Handling**: Proper exception handling and logging throughout  
✅ **Resource Cleanup**: Proper cleanup in disable methods  
✅ **Permission Security**: Permission checks before sensitive operations  
✅ **Internationalization**: Multi-language support with fallbacks  
✅ **Testing**: Comprehensive unit test coverage  
✅ **Documentation**: Extensive code comments and external documentation

## Development Workflow

1. **Start Clean**: Disable all example features initially
2. **Enable Examples**: Turn on features you want to learn from
3. **Study Code**: Review example implementations
4. **Customize**: Replace examples with your own functionality
5. **Test**: Write and run tests for your features
6. **Document**: Update README and documentation

## Troubleshooting

**Plugin Not Loading?**

- Verify `plugin.yml` format and main class path
- Check for dependency conflicts
- Review logs for specific error messages

**Examples Not Working?**

- Ensure features are enabled in `config.yml`
- Check permission configuration
- Verify language files are properly formatted

**Build Issues?**

- Ensure Java 25+ and Maven 3.6+
- Check parent POM compatibility
- Verify all dependencies are available

## Support

- **Documentation**: [Full documentation](../docs/)
- **Issues**: Report issues on the main repository
- **Community**: Join discussions for help and best practices

---

**Fluxcord Plugin Template** - *A comprehensive foundation for professional Discord bot plugin development.*