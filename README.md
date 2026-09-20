# Fluxcord

[![CI](https://github.com/FarmVivi/fluxcord/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/FarmVivi/fluxcord/actions/workflows/ci.yml)
[![Docker Image](https://github.com/FarmVivi/fluxcord/actions/workflows/docker-image-ci.yml/badge.svg?branch=develop)](https://github.com/FarmVivi/fluxcord/actions/workflows/docker-image-ci.yml)
[![Latest release](https://img.shields.io/github/v/release/FarmVivi/fluxcord?sort=semver)](https://github.com/FarmVivi/fluxcord/releases/latest)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=FarmVivi_fluxcord&metric=coverage&token=846a9a5e802338d174e0fed749182d29d265fc37)](https://sonarcloud.io/summary/new_code?id=FarmVivi_fluxcord)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=FarmVivi_fluxcord&metric=alert_status&token=846a9a5e802338d174e0fed749182d29d265fc37)](https://sonarcloud.io/summary/new_code?id=FarmVivi_fluxcord)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=FarmVivi_fluxcord&metric=bugs&token=846a9a5e802338d174e0fed749182d29d265fc37)](https://sonarcloud.io/summary/new_code?id=FarmVivi_fluxcord)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=FarmVivi_fluxcord&metric=code_smells&token=846a9a5e802338d174e0fed749182d29d265fc37)](https://sonarcloud.io/summary/new_code?id=FarmVivi_fluxcord)

A modular, generic Discord bot engine designed for flexibility and extensibility. Built with a multi-module Maven
architecture, it provides a comprehensive plugin system allowing developers to create powerful Discord bots with minimal
effort.

> [!IMPORTANT]
> Fluxcord is in active development.
> - `develop`: v3 modular refactor (current active branch)
> - `main`: v2 legacy monolith (legacy branch with integrated modules such as music)

## 🚀 Features

### Core Engine Features

- **Multi-Module Architecture**: Clean separation between API contracts and implementations
- **Plugin System**: Dynamic plugin loading with dependency resolution and lifecycle management
- **Command Framework**: Unified slash commands and text commands with permissions, cooldowns, and categories
- **Audio API**: Advanced audio processing with send/receive streams, mixing, and priority management
- **Permission System**: Flexible role-based permissions with plugin-specific controls
- **Internationalization**: Full i18n support with language switching and namespace management
- **Configuration Management**: YAML-based configuration with environment variable overrides
- **Event System**: Comprehensive event handling with priority-based execution
- **Storage API**: Pluggable storage backends (File, Database, S3) for data persistence
- **Discord Integration**: Full Discord API coverage through JDA with abstractions

### Built-in Commands

- `help` - Interactive help system with command discovery
- `version` - Display bot and plugin version information
- `shutdown` - Graceful bot shutdown with plugin cleanup

## 🎵 Available Plugins

The Fluxcord ecosystem includes several ready-to-use plugins:

### 🎵 Music Plugin

Advanced music bot functionality with playlist management, queue controls, and audio effects.

- Play music from YouTube, Spotify, SoundCloud
- Playlist creation and management
- Queue controls (skip, shuffle, loop)
- Volume control and audio filters
- Now playing displays with rich embeds

### 🤖 AI Audio Plugin

AI-powered voice processing capabilities for enhanced user interaction.

- Voice recognition and transcription
- Text-to-speech with multiple voices
- Audio analysis and processing
- Integration with popular AI services

### 🔊 Audio Example Plugin

Demonstration plugin showing audio API capabilities.

- Basic audio playback and recording
- Voice channel event handling
- Audio stream processing examples

*More plugins are in development and will be released soon!*

## 🏗️ Architecture

```
fluxcord/
├── fluxcord-api/                          # Public API interfaces and contracts
├── fluxcord-core/                         # Core implementation and engine
├── examples/plugins/plugin-example-audio/ # Example audio plugin
├── plugin-template/                       # Template for creating new plugins
├── plugins/                               # Additional plugin modules
│   ├── music-plugin/                      # Music bot functionality
│   └── ai-audio-plugin/                   # AI voice processing
└── docs/                                  # Documentation and guides
```

## 🚀 Quick Start

### Prerequisites

- **Java 25** or newer
- **Maven 3.6+** for building
- **Discord Bot Token** (obtain from Discord Developer Portal)

### Installation

1. **Clone the repository**

```bash
git clone https://github.com/FarmVivi/fluxcord.git
cd fluxcord
```

2. **Build the project**

```bash
mvn clean package
```

3. **Configure the bot (`develop` / v3)**

```bash
cp fluxcord-core/src/main/resources/config.yml config.yml
# Edit config.yml and set discord.token
```

4. **Run the bot**

```bash
java -jar fluxcord-core/target/*-shaded.jar
```

> [!NOTE]
> Legacy v2 (`main`) uses `config.json` (not YAML).

### Docker Deployment

Fluxcord provides multiple optimized Docker build options for different use cases:

#### 🚀 Quick Start (Recommended)

```bash
# Build and run with optimized Dockerfile
docker build -f Dockerfile.optimized -t fluxcord:latest .
docker run -d --name fluxcord -p 8081:8081 fluxcord:latest
```

#### ⚡ Maximum Performance (BuildKit)

For the fastest builds with persistent caching:

```bash
# Enable BuildKit and use optimized Dockerfile with cache mounts
DOCKER_BUILDKIT=1 docker build -f Dockerfile.buildkit -t fluxcord:latest .
docker run -d --name fluxcord -p 8081:8081 fluxcord:latest
```

#### 🛠️ Automated Build Script

Use the provided build script for automatic optimization detection:

```bash
# Automatically detects BuildKit and uses the best Dockerfile
./build-optimized.sh
```

#### 📦 Docker Compose

A `docker-compose.yml` is provided for containerized deployment:

```bash
docker compose up --build
```

This automatically builds the application and provides persistent storage for data, plugins, and configuration.

#### 🎯 Build Performance

The optimized Dockerfiles provide significant performance improvements:

- **First build**: Same time (dependencies downloaded)
- **Subsequent builds**: 60-80% faster (Maven dependency cache)
- **With BuildKit**: 70-90% faster (persistent cache + optimizations)
- **Identical builds**: 95% faster (full cache hit)

#### 🔧 Build Options

| Dockerfile             | Use Case             | Performance | Requirements                |
|------------------------|----------------------|-------------|-----------------------------|
| `Dockerfile.optimized` | Standard builds      | ⭐⭐⭐         | Docker 17.06+               |
| `Dockerfile.buildkit`  | Maximum performance  | ⭐⭐⭐⭐⭐       | Docker 18.09+ with BuildKit |
| `Dockerfile`           | Legacy compatibility | ⭐           | Any Docker version          |

> **⚠️ Voice/DAVE native (JDAVE)** — JDA audio requires a DAVE implementation.
> We use [JDAVE](https://github.com/MinnDevelopment/jdave), the JDA maintainer's implementation,
> which ships **prebuilt** native `libdave` binaries via Maven (`jdave-native-linux-x86-64` /
> `-aarch64` / `win-x86-64`). These are **glibc** only and need **GLIBC ≥ 2.38**, so the runtime
> images use a glibc base (`eclipse-temurin:…-jre-noble`, Ubuntu 24.04) rather than Alpine/musl —
> no native compilation is needed. If you deploy outside Docker, use a distro with glibc ≥ 2.38
> (Ubuntu 24.04+, Debian 13+); older glibc fails with `Cannot open library … dave*.so`.

#### 📊 Performance Testing

Test build performance with the provided script:

```bash
# Compare build times across different Dockerfiles
./test-optimizations.sh
```

For detailed optimization information, see [DOCKER_OPTIMIZATION.md](DOCKER_OPTIMIZATION.md).

## 🔌 Plugin Development

Creating plugins for Fluxcord is straightforward thanks to the provided template and comprehensive API.

### Using the Plugin Template

1. **Copy the template**

```bash
cp -r plugin-template my-awesome-plugin
cd my-awesome-plugin
```

2. **Customize the plugin**

- Edit `pom.xml` to change artifact ID and details
- Implement your plugin logic in the main class
- Add commands, event handlers, and features

3. **Build and install**

```bash
mvn clean package
cp target/my-awesome-plugin-*.jar ../plugins/
```

### Plugin API Overview

```java
@Plugin(name = "MyPlugin", version = "1.0.0")
public class MyAwesomePlugin extends AbstractPlugin {
    
    @Override
    public void onEnable() {
        // Plugin initialization
        registerCommands();
        setupEventHandlers();
    }
    
    @Command(name = "hello", description = "Say hello!")
    public void helloCommand(CommandContext ctx) {
        ctx.reply("Hello from my plugin!");
    }
    
    private void setupEventHandlers() {
        // Discord events: a JDA listener, removed automatically when the plugin is disabled
        addDiscordListeners(new ListenerAdapter() {
            @Override
            public void onMessageReceived(MessageReceivedEvent event) { /* ... */ }
        });
    }
}
```

### Available APIs

- **Command API**: Create slash commands and text commands
- **Event API**: Handle Discord and plugin events
- **Audio API**: Process audio streams and voice channels
- **Permission API**: Manage user permissions and roles
- **Storage API**: Persist data across restarts
- **Configuration API**: Manage plugin settings
- **Language API**: Support multiple languages

## 📖 Documentation

Comprehensive documentation is available in the `docs/` directory:

- [Plugin Development Guide](docs/plugin-development.md)
- [Command System](docs/commands.md)
- [Audio API](docs/audio-api.md)
- [Configuration Guide](docs/configuration.md)
- [Event System](docs/events.md)
- [Core Features Matrix](docs/core-features.md): Complete overview of all 12 framework features
- [Plugin Template Guide](docs/plugins/template-quickstart.md): Step-by-step plugin development guide
- [Example Plugins Overview](docs/plugins/examples-overview.md): Learning resources for all features

## 🔧 Development

### Building from Source

```bash
# Compile all modules
mvn clean compile

# Run tests
mvn test

# Package with dependencies (optimized)
mvn -T1C clean package -DskipTests

# Install to local repository
mvn clean install

# Full verification, exactly what CI runs (tests + JaCoCo report in */target/site/jacoco/)
mvn -B verify
```

### Running locally

The bot reads `config.yml`, `plugins/`, `lang/` and writes `logs/` relative to its working directory.
`fluxcord-core/run/` is git-ignored and meant for that:

```bash
# One-time setup
mkdir -p fluxcord-core/run/plugins
cp fluxcord-core/src/main/resources/config.yml fluxcord-core/run/config.yml   # then set discord.token
cp plugins/music-plugin/target/*-shaded.jar fluxcord-core/run/plugins/          # optional plugins

# Start with the dev logging config (forked JVM, working directory fluxcord-core/run/)
mvn -pl fluxcord-core exec:exec

# Same without Maven (the console reads stdin: type `shutdown` for a clean stop)
cd fluxcord-core/run
java -Dlogback.configurationFile=logback-dev.xml --enable-native-access=ALL-UNNAMED -jar ../target/fluxcord-core-*-shaded.jar
```

Health endpoints while running: `http://localhost:8081/healthz`, `/readyz`, `/version` (port via `HEALTH_PORT`).

### Continuous integration

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `ci.yml` | push/PR on `develop`, `main` | `mvn -B verify`: build + tests + JaCoCo reports (artifacts) |
| `sonarqube-analysis.yml` | push/PR | SonarCloud analysis with test coverage |
| `docker-image-ci.yml` | push/PR/tags | builds the image; publishes to GHCR on `develop`, `main` and `v*` tags |
| `release-build.yml` | GitHub release | attaches the shaded jar to the release |
| `bump-*-version.yml` | manual | bumps the reactor version |

### Docker Development

For development with Docker, use the optimized build options:

```bash
# Development build with optimizations
docker build -f Dockerfile.optimized -t fluxcord:dev .

# Development build with BuildKit (fastest)
DOCKER_BUILDKIT=1 docker build -f Dockerfile.buildkit -t fluxcord:dev .

# Run development container
docker run -it --rm -v $(pwd):/workspace fluxcord:dev
```

### Performance Optimization

The project includes several optimization features:

- **Maven Build Optimization**: Parallel builds with `-T1C`, skip unnecessary steps
- **Docker Layer Caching**: Optimized Dockerfiles with dependency caching
- **BuildKit Integration**: Advanced caching with cache mounts
- **Multi-stage Builds**: Minimal production images
- **Repository Optimization**: Fast Maven Central mirrors

See [DOCKER_OPTIMIZATION.md](DOCKER_OPTIMIZATION.md) for detailed optimization information.

### Module Structure

- **fluxcord-api**: Lightweight API interfaces for plugin development
- **fluxcord-core**: Main engine implementation with all features
- **plugin-example-audio**: Reference implementation for audio plugins
- **plugin-example-commands**: Reference implementation for command handling
- **plugin-template**: Starter template for new plugin development

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details on:

- Code style and standards
- Pull request process
- Issue reporting
- Plugin submission guidelines

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

- **Documentation**: Check the `docs/` directory
- **Issues**: Report bugs or request features on GitHub Issues
- **Discussions**: Join community discussions on GitHub Discussions
- **Discord**: Join our development Discord server (link in issues)

## 🌟 Showcase

Built something amazing with Fluxcord? We'd love to showcase it! Open an issue or discussion to share your creation.

---

**Fluxcord** - *Empowering Discord bot development with modular architecture and comprehensive APIs.*
