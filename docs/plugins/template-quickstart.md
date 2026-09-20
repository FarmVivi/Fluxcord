# Plugin Template Quickstart Guide

This guide provides step-by-step instructions for using the Fluxcord plugin template to create your own plugins.

## Table of Contents

1. [Template Overview](#template-overview)
2. [Getting Started](#getting-started)
3. [Template Structure](#template-structure)
4. [Configuration System](#configuration-system)
5. [Feature Examples](#feature-examples)
6. [Customization Guide](#customization-guide)
7. [Building and Testing](#building-and-testing)
8. [Deployment](#deployment)
9. [Troubleshooting](#troubleshooting)

## Template Overview

The plugin template provides a comprehensive starting point that demonstrates all core features of the Fluxcord
framework:

- **Plugin Lifecycle**: Complete lifecycle management with all phases
- **Configuration System**: YAML-based configuration with feature toggles
- **Command System**: Example command registration and handling
- **Event System**: Discord and plugin event handling
- **Permission System**: Role-based permissions with namespacing
- **Internationalization**: Multi-language support (English and French)
- **Data Storage**: Persistent data storage with scoping
- **Service Architecture**: Organized code structure with services
- **Unit Testing**: Complete test examples with JUnit 5 and Mockito

### Key Features

✅ **Modular Design**: All examples can be disabled via configuration  
✅ **Best Practices**: Follows recommended patterns and conventions  
✅ **Comprehensive**: Covers all framework features  
✅ **Well Documented**: Extensive comments and documentation  
✅ **Test Ready**: Includes unit tests and test configuration

## Getting Started

### Prerequisites

- Java 25 or newer
- Maven 3.6+ for building
- Fluxcord framework
- IDE with Java support (IntelliJ IDEA recommended)

### Step 1: Copy the Template

```bash
# Copy the entire template directory
cp -r plugin-template my-awesome-plugin
cd my-awesome-plugin
```

### Step 2: Update Project Metadata

Edit `pom.xml`:

```xml
<artifactId>my-awesome-plugin</artifactId>
<name>My Awesome Plugin</name>
<description>Description of what your plugin does</description>
```

Edit `src/main/resources/plugin.yml`:

```yaml
name: MyAwesomePlugin
version: 1.0.0
main: com.yourcompany.plugin.MyAwesomePlugin
description: Your plugin description
author: YourName
website: https://github.com/yourusername/your-plugin-repo
dependencies: []
```

### Step 3: Rename Package and Class

1. **Rename the package**:
    - From: `com.example.plugin`
    - To: `com.yourcompany.plugin`

2. **Rename the main class**:
    - From: `TemplatePlugin.java`
    - To: `MyAwesomePlugin.java`

3. **Update imports** in all files to use your new package name

### Step 4: Configure Features

Edit `src/main/resources/config.yml` to enable/disable features:

```yaml
# Enable or disable example features
features:
  example_commands: true    # Enable to see command examples
  example_events: true      # Enable to see event examples  
  example_storage: true     # Enable to see storage examples
```

## Template Structure

```
my-awesome-plugin/
├── pom.xml                                # Maven configuration
├── CHANGELOG.md                           # Version history
├── README.md                              # Plugin documentation
├── src/
│   ├── main/
│   │   ├── java/com/example/plugin/
│   │   │   ├── TemplatePlugin.java        # Main plugin class
│   │   │   ├── commands/
│   │   │   │   └── ExampleCommand.java    # Command examples
│   │   │   ├── events/
│   │   │   │   └── ExampleEventListener.java # Event examples
│   │   │   └── services/
│   │   │       └── ExampleDataService.java   # Service examples
│   │   └── resources/
│   │       ├── plugin.yml                 # Plugin metadata
│   │       ├── config.yml                 # Plugin configuration
│   │       └── lang/                      # Language files
│   │           ├── en-US.yml              # English translations
│   │           └── fr-FR.yml              # French translations
│   └── test/
│       └── java/com/example/plugin/
│           └── TemplatePluginTest.java    # Unit tests
```

## Configuration System

The template uses a hierarchical YAML configuration system with feature toggles:

### Main Configuration (`config.yml`)

```yaml
# Plugin features control
features:
  example_commands: false    # Disable to remove command examples
  example_events: false      # Disable to remove event examples
  example_storage: false     # Disable to remove storage examples

# Command system settings
commands:
  enabled: true
  prefix: "!"
  cooldown: 3

# Permission settings
permissions:
  default_permission: true
  admin_nodes:
    - "myplugin.admin"
    - "myplugin.config"

# Storage settings  
storage:
  enabled: true
  format: "json"
  autosave_interval: 30

# Debug settings
debug:
  log_messages: false
  extended_debug: false
```

### Configuration Best Practices

1. **Use feature toggles** to enable/disable functionality
2. **Provide sensible defaults** for all settings
3. **Group related settings** under sections
4. **Document configuration options** in comments
5. **Validate configuration values** in your plugin code

## Feature Examples

### 1. Command System

**File**: `commands/ExampleCommand.java`

```java
public CommandResult execute(CommandContext context) {
    // Check permissions
    if (!plugin.getPermissions().hasPermission(
            context.getUser().getId(), "template.use")) {
        String message = plugin.getLanguage()
                .getString("errors.no_permission");
        context.reply(message);
        return CommandResult.error("No permission");
    }
    
    // Get localized response
    String response = plugin.getLanguage()
            .getString("messages.example_message");
    
    context.reply(response);
    return CommandResult.success();
}
```

**Key Points**:

- Permission checks before execution
- Localized error and success messages
- Proper result handling

### 2. Event Handling

**File**: `events/ExampleEventListener.java`

```java
// ExampleEventListener extends ListenerAdapter: Discord events are JDA listener overrides,
// registered with addDiscordListeners(listener); @EventHandler is only for Fluxcord events.
@Override
public void onMessageReceived(MessageReceivedEvent event) {
    // Skip bot messages
    if (event.getAuthor().isBot()) {
        return;
    }
    
    // Check if feature is enabled
    if (!plugin.getConfiguration().getBoolean("events.enabled", true)) {
        return;
    }
    
    // Process the event
    plugin.logger.debug("Message received from {}", 
                       event.getAuthor().getAsTag());
}
```

**Key Points**:

- Proper event filtering (skip bots)
- Configuration-based enabling/disabling
- Appropriate logging levels

### 3. Data Storage

**File**: `services/ExampleDataService.java`

```java
public void saveUserPreference(String userId, String key, Object value) {
    try {
        plugin.getStorage().getUserStorage(userId).set(key, value);
        plugin.getStorage().saveAll();
        
        plugin.logger.debug("Saved user preference: {} = {} for user {}", 
                           key, value, userId);
    } catch (Exception e) {
        plugin.logger.error("Failed to save user preference", e);
    }
}
```

**Key Points**:

- Proper error handling
- Appropriate logging
- Different storage scopes (user, guild, global)

### 4. Internationalization

**Language Files** (`lang/en-US.yml`, `lang/fr-FR.yml`):

```yaml
template:
  messages:
    welcome_user: "Welcome, {0}!"
    example_message: "Hello from Template Plugin!"
  
  errors:
    no_permission: "You don't have permission to use this command"
    
  commands:
    help: "Show template plugin help"
```

**Usage in Code**:

```java
// Simple message
String message = getLanguage().getString("messages.example_message");

// Message with placeholders
String welcome = getLanguage().getString("messages.welcome_user", username);
```

## Customization Guide

### 1. Adding New Commands

1. Create command class in `commands/` package
2. Implement command logic with permission checks
3. Register command in main plugin class:

```java
getCommands().registerCommand(builder -> {
    builder.name("mycommand")
           .description("My custom command")
           .executor((context, cmd) -> myCommand.execute(context));
});
```

### 2. Adding New Events

1. Create event listener class in `events/` package
2. Add `ListenerAdapter` overrides for Discord events and `@EventHandler` methods for Fluxcord events
3. Register listener in main plugin class:

```java
eventManager.registerListener(myEventListener, this);
```

### 3. Adding New Services

1. Create service class in `services/` package
2. Initialize service in main plugin class
3. Use dependency injection pattern for service access

### 4. Adding New Permissions

```java
getPermissions().registerPermission(new SimplePermission(
    pluginPrefix("custom.permission"),
    "Description of the permission",
    PermissionDefault.FALSE
));
```

### 5. Adding New Configuration Options

1. Add options to `config.yml`
2. Access in code: `getConfiguration().getString("your.setting", "default")`
3. Validate configuration values appropriately

## Building and Testing

### Build the Plugin

```bash
# Compile and package
mvn clean package

# The JAR will be in target/ directory
ls target/*.jar
```

### Run Tests

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=TemplatePluginTest

# Run tests with coverage
mvn test jacoco:report
```

### Test Configuration

The template includes JUnit 5 and Mockito dependencies:

```xml
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

### Example Test

```java
@ExtendWith(MockitoExtension.class)
public class MyPluginTest {
    
    @Mock
    private PluginContext mockContext;
    
    private MyPlugin plugin;
    
    @BeforeEach
    void setUp() {
        plugin = new MyPlugin();
        when(mockContext.getPluginName()).thenReturn("MyPlugin");
        // Setup other mocks...
    }
    
    @Test
    void testPluginEnables() {
        plugin.onLoad(mockContext);
        plugin.onEnable();
        
        assertTrue(plugin.isEnabled());
    }
}
```

## Deployment

### 1. Build for Production

```bash
# Build with production profile (if configured)
mvn clean package -Pproduction

# Or standard build
mvn clean package
```

### 2. Deploy to Bot

```bash
# Copy JAR to plugins directory
cp target/my-awesome-plugin-1.0.0.jar /path/to/bot/plugins/

# Restart bot or reload plugins (if supported)
```

### 3. Configuration

1. Copy default configuration if needed
2. Customize settings for your environment
3. Test functionality with example features enabled

## Troubleshooting

### Common Issues

**Plugin Not Loading**

- Check plugin.yml format and syntax
- Verify main class path is correct
- Check for missing dependencies

**Configuration Errors**

- Validate YAML syntax in config.yml
- Check for missing configuration values
- Review error logs for specific issues

**Permission Issues**

- Verify permissions are registered in onPreEnable()
- Check permission node names for typos
- Ensure permission defaults are appropriate

**Language Issues**

- Check language file format and structure
- Ensure language keys match code usage

### Debug Mode

Enable extended debugging in config.yml:

```yaml
debug:
  extended_debug: true
  log_messages: true
  log_plugin_events: true
```

### Logging Levels

Use appropriate logging levels:

```java
logger.trace("Detailed execution flow");    // Very verbose
logger.debug("Debug information");          // Development info
logger.info("General information");         // Normal operation
logger.warn("Warning about potential issues"); // Warnings
logger.error("Error occurred", exception);  // Errors
```

## Next Steps

1. **Review Core Features**: Read [core-features.md](../core-features.md) for complete API reference
2. **Explore Examples**: Check [examples-overview.md](examples-overview.md) for specialized examples
3. **Join Community**: Connect with other plugin developers
4. **Contribute**: Share your plugin with the community

---

*This guide covers the essential aspects of plugin development using the template. For advanced features and specific
use cases, refer to the core documentation and API reference.*