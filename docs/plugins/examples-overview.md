# Plugin Examples Overview

This directory contains comprehensive example plugins demonstrating all core features of the Fluxcord framework. Each
plugin focuses on specific functionality while being minimal, complete, and production-ready.

## Available Examples

### ✅ Implemented Examples

#### [Command System Example](plugin-example-commands/)

**Focus**: Complete command system demonstration  
**File**: `plugin-example-commands/`  
**Status**: ✅ Fully Implemented

**Features Demonstrated**:

- Slash command registration and handling
- Command arguments with validation
- Permission-based access control
- Cooldown management systems
- Rich embed responses
- Error handling and user feedback
- Command statistics and monitoring
- Multi-language support
- Configuration-driven behavior

**Commands Available**:

- `/ping` - Basic responsiveness test
- `/echo <message>` - Argument handling and validation
- `/info` - Rich embeds with system information
- `/admin` - Permission checks and access control

**Key Learning Points**:

- How to register different types of commands
- Implementing permission checks effectively
- Creating rich Discord embeds
- Managing command cooldowns and rate limiting
- Proper error handling and user communication

---

### 🚧 Planned Examples

#### Event System Example

**Focus**: Discord and plugin event handling  
**File**: `plugin-example-events/` *(Coming Soon)*  
**Status**: 📋 Planned

**Features to Demonstrate**:

- Discord event listeners (JDA `ListenerAdapter` via `addDiscordListeners`) and Fluxcord events (`@EventHandler`)
- Event priorities and execution order
- Event filtering and conditional processing
- Plugin lifecycle events
- Custom event creation and firing
- Async event handling

#### Permission System Example

**Focus**: Advanced permission management  
**File**: `plugin-example-permissions/` *(Coming Soon)*  
**Status**: 📋 Planned

**Features to Demonstrate**:

- Permission registration and management
- Role-based access control
- Permission inheritance and defaults
- Dynamic permission assignment
- Permission-based feature toggling
- Guild-specific permission overrides

#### Storage System Example

**Focus**: Data persistence and management  
**File**: `plugin-example-storage/` *(Coming Soon)*  
**Status**: 📋 Planned

**Features to Demonstrate**:

- Global, user, and guild data storage
- Data serialization and deserialization
- Storage migrations and versioning
- Binary file storage examples
- Storage performance optimization
- Backup and restore functionality

#### Internationalization Example

**Focus**: Multi-language support  
**File**: `plugin-example-i18n/` *(Coming Soon)*  
**Status**: 📋 Planned

**Features to Demonstrate**:

- Language namespace registration
- Dynamic language switching
- Placeholder and formatting support
- Language file organization
- Fallback language handling
- User preference language storage

#### Configuration Example

**Focus**: Advanced configuration management  
**File**: `plugin-example-config/` *(Coming Soon)*  
**Status**: 📋 Planned

**Features to Demonstrate**:

- Configuration loading and validation
- Environment variable substitution
- Hot configuration reloading
- Configuration migrations
- Nested configuration structures
- Configuration change events

#### Audio System Example *(Already Exists)*

**Focus**: Audio processing and voice channels  
**File**: `plugin-example-audio/` *(Existing under examples/plugins)*  
**Status**: ✅ Available

**Features Demonstrated**:

- Audio send/receive handlers
- Voice channel management
- Audio mixing and processing
- Priority-based audio management
- Audio stream handling

## Usage Instructions

### General Setup

1. **Build Example Plugin**:
   ```bash
   cd examples/plugins/[plugin-name]
   mvn clean package
   ```

2. **Install Plugin**:
   ```bash
   cp target/[plugin-name]-*.jar /path/to/bot/plugins/
   ```

3. **Enable Plugin**:
   Edit the plugin's `config.yml`:
   ```yaml
   enabled: true  # All examples are disabled by default
   ```

4. **Restart Bot**:
   Restart your Fluxcord instance

### Example-Specific Setup

Each example plugin includes:

- **README.md**: Detailed usage instructions and feature documentation
- **config.yml**: Comprehensive configuration with feature toggles
- **lang/**: Multi-language support (English and French)
- **plugin.yml**: Plugin metadata and dependencies

### Configuration Philosophy

All example plugins follow these principles:

✅ **Disabled by Default**: Examples don't interfere with production usage  
✅ **Feature Toggles**: Individual features can be enabled/disabled  
✅ **Rich Configuration**: Extensive customization options  
✅ **Debug Support**: Configurable logging and debugging features  
✅ **Safe Defaults**: Production-safe default settings

## Learning Path Recommendations

### For Beginners

1. **Start with Commands**: [`plugin-example-commands/`](plugin-example-commands/) - Learn basic plugin structure
2. **Add Events**: `plugin-example-events/` *(Coming Soon)* - Understand event handling
3. **Explore Storage**: `plugin-example-storage/` *(Coming Soon)* - Learn data persistence

### For Intermediate Developers

1. **Master Permissions**: `plugin-example-permissions/` *(Coming Soon)* - Advanced access control
2. **Internationalize**: `plugin-example-i18n/` *(Coming Soon)* - Multi-language support
3. **Advanced Config**: `plugin-example-config/` *(Coming Soon)* - Complex configuration management

### For Advanced Usage

1. **Audio Processing**: [`../audio/`](../audio/) - Voice channel and audio handling
2. **Custom Integration**: Combine multiple examples for complex functionality
3. **Performance Optimization**: Study production patterns and best practices

## Code Quality Standards

All examples maintain high code quality:

- **📝 Documentation**: Comprehensive JavaDoc and inline comments
- **🧪 Testing**: Unit tests where applicable (following existing patterns)
- **🎯 Best Practices**: Production-ready code patterns
- **🔧 Error Handling**: Proper exception handling and user feedback
- **📊 Logging**: Appropriate logging levels and debug information
- **🌐 Internationalization**: Multi-language support
- **⚙️ Configuration**: Extensive customization options

## Integration with Core Documentation

These examples integrate with the main documentation:

- **[Core Features Matrix](../../core-features.md)**: Links to relevant examples
- **[Plugin Development Guide](../../plugin-development.md)**: References example implementations
- **[Template Quickstart](template-quickstart.md)**: Uses examples for learning

## Contributing New Examples

When adding new examples:

1. **Follow Structure**: Use existing examples as templates
2. **Disable by Default**: Set `enabled: false` in default config
3. **Comprehensive README**: Document features, setup, and usage
4. **Multi-language**: Provide EN and FR language files
5. **Rich Configuration**: Include extensive configuration options
6. **Production Ready**: Ensure code quality and error handling

## Example Plugin Structure

Standard structure for all examples:

```
plugin-example-[feature]/
├── pom.xml                     # Maven configuration
├── README.md                   # Comprehensive documentation
├── src/main/
│   ├── java/fr/farmvivi/fluxcord/examples/[feature]/
│   │   └── [Feature]ExamplePlugin.java
│   └── resources/
│       ├── plugin.yml          # Plugin metadata
│       ├── config.yml          # Feature-rich configuration
│       └── lang/               # Internationalization
│           ├── en-US.yml       # English translations
│           └── fr-FR.yml       # French translations
└── src/test/                   # Unit tests (when applicable)
```

## Support and Community

- **Documentation**: Each example includes comprehensive documentation
- **Issues**: Report issues on the main repository
- **Discussions**: Join community discussions for help and best practices
- **Contributions**: Submit examples for additional features

---

*Example plugins are maintained as part of the Fluxcord documentation project and serve as definitive implementation
references for plugin developers.*