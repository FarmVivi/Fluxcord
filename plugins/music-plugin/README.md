# Music Plugin for Fluxcord

Advanced music bot functionality for Fluxcord with comprehensive playlist management, multi-source support, and
persistent player controls.

## 🎵 Features

### Music Playback

- **Multi-platform Support**: YouTube, Spotify, SoundCloud, Deezer, Apple Music, Bandcamp, Vimeo, Twitch, and more
- **High-quality Audio**: Lossless audio streaming with configurable quality settings
- **Smart Search**: Intelligent search across multiple platforms with fallback support
- **Direct URL Support**: Play from direct links or import entire playlists

### Queue Management

- **Dynamic Queue**: Add, remove, move, and reorder tracks
- **Queue Persistence**: Queues are maintained even during bot restarts
- **Advanced Modes**: Shuffle, loop track, loop queue functionality
- **Smart Skipping**: Vote skip system with configurable thresholds

### Persistent Player Controls

- **Interactive Player Message**: Always-visible control panel with real-time updates
- **Button Controls**: Play/pause, skip, stop, volume, loop, shuffle via buttons
- **Progress Tracking**: Visual progress bar with time display
- **Auto-cleanup**: Smart message management to avoid clutter

### Playlist System

- **Personal Playlists**: Create and manage private playlists
- **Server Playlists**: Collaborative playlists for your community
- **Import/Export**: Backup and share playlists easily
- **Quick Access**: Load entire playlists with a single command

### Audio Control

- **Volume Control**: Per-server volume settings with memory
- **Seek Function**: Jump to any position in the current track
- **Audio Priority**: Smart audio mixing when multiple sources play
- **Auto-disconnect**: Configurable timeout for inactive sessions

## 📋 Commands

| Command              | Description                     | Permission       |
|----------------------|---------------------------------|------------------|
| `/play <query>`      | Play music from search or URL   | `music-plugin.play`     |
| `/pause`             | Pause/resume playback           | `music-plugin.play`     |
| `/skip`              | Skip current track              | `music-plugin.skip`     |
| `/stop`              | Stop playback and clear queue   | `music-plugin.play`     |
| `/queue [page]`      | Show current queue              | `music-plugin.queue`    |
| `/nowplaying`        | Show current track info         | `music-plugin.queue`    |
| `/volume [level]`    | View or set volume (0-100)      | `music-plugin.volume`   |
| `/loop [mode]`       | Set loop mode (off/track/queue) | `music-plugin.queue`    |
| `/shuffle`           | Toggle shuffle mode             | `music-plugin.queue`    |
| `/clear`             | Clear the queue                 | `music-plugin.admin`    |
| `/remove <position>` | Remove track from queue         | `music-plugin.queue`    |
| `/seek <time>`       | Seek to position in track       | `music-plugin.play`     |
| `/playlist <action>` | Manage playlists                | `music-plugin.playlist` |

## 🔧 Installation & Configuration

### Requirements

- Fluxcord 2.3.27 or higher
- Java 25 or higher
- (Optional) API keys for premium sources (Spotify, Apple Music, etc.)

### Basic Setup

1. Place the plugin JAR in your `plugins/` directory
2. Restart Fluxcord to generate configuration files
3. Configure API keys in `plugins/music-plugin/config.yml` (optional)
4. Restart again to apply configuration

### Provider Configuration

#### Spotify

```yaml
providers:
  spotify:
    enabled: true
    client_id: "your-client-id"
    client_secret: "your-client-secret"
```

#### Apple Music

```yaml
providers:
  apple_music:
    token: "your-api-token"
    country_code: "US"
```

#### Deezer

```yaml
providers:
  deezer:
    master_decryption_key: "your-key"
```

## 🎛️ Advanced Configuration

### Playlist Limits

```yaml
playlists:
  max_per_user: 10
  max_per_guild: 25
  max_tracks: 100
```

### Auto-leave Settings

```yaml
music:
  auto_leave_timeout: 300000  # 5 minutes in ms
```

## 🔌 Integration with Fluxcord APIs

This plugin fully utilizes Fluxcord's powerful APIs:

- **Audio Service**: Multi-source audio mixing with priority management
- **Command Service**: Slash commands with full autocomplete and validation
- **i18n Service**: Complete multi-language support (English, French included)
- **Permission Service**: Fine-grained permission control
- **Storage Service**: Persistent data storage for playlists and settings
- **Event System**: React to voice events and user interactions

## 🌐 Internationalization

The plugin includes full translations for:

- English (en-US)
- French (fr-FR)

Additional languages can be added by creating new files in `lang/` directory.

## 🛠️ Development

### Building from Source

```bash
cd plugins/music-plugin
mvn clean package
```

### Adding New Providers

1. Add dependencies to `pom.xml`
2. Register source manager in `AudioPlayerManager`
3. Add configuration options
4. Update documentation

### Extending Commands

Commands follow Fluxcord's command pattern:

```java
commandService.registerCommand(plugin, builder -> {
    builder.name("mycommand")
           .description("My command description")
           .executor((ctx, cmd) -> {
               // Command logic
               return CommandResult.SUCCESS;
           });
});
```

## 📊 Performance Considerations

- The plugin uses efficient audio buffering to minimize latency
- Queue operations are O(1) for most common operations
- Player messages update with throttling to avoid rate limits
- Audio sources are initialized lazily to reduce memory usage

## 🐛 Troubleshooting

### Bot doesn't join voice channel

- Ensure bot has `Connect` and `Speak` permissions
- Check if voice region is supported

### No sound playing

- Verify volume isn't set to 0
- Check if track is actually supported by the source
- Review logs for loading errors

### Player message not updating

- Ensure bot has `Manage Messages` permission
- Check if channel allows embedded messages
- Verify message hasn't been manually deleted

### High latency/lag

- Reduce buffer sizes in performance settings
- Ensure good connection to Discord voice servers
- Consider enabling native transport

## 📄 License

This plugin is part of Fluxcord and is licensed under the same terms.