# Configuration Guide

This guide documents the configuration system for Fluxcord.

## Configuration Files

### Core configuration

- File name: `config.yml`
- Location: project root (same working directory used to start the bot)
- Behavior: automatically created on first startup from core resources when missing

### Plugin configuration

For each plugin, Fluxcord uses:

- `plugins/<plugin-id>/config.yml`

If the file is missing, Fluxcord tries to copy `config.yml` from the plugin JAR resources.

## Default Core Configuration

Current default template (from `fluxcord-core/src/main/resources/config.yml`):

```yaml
# Discord Bot Configuration
config_version: 1

discord:
  token: YOUR_BOT_TOKEN

# Language settings
language:
  default: en-US

# Command system settings
commands:
  default-prefix: "!"  # Default prefix for text commands
  cooldown: 3  # Global default cooldown in seconds
  system:
    help: true      # Enable/disable help command
    version: true   # Enable/disable version command
    shutdown: true  # Enable/disable shutdown command

# Data storage settings
data:
  storage:
    type: FILE  # Options: FILE, DB
    db:
      url: "jdbc:mysql://localhost:3306/fluxcord"
      username: username
      password: password

  # Binary storage settings for large files
  binary:
    storage:
      type: FILE  # Options: FILE, S3
      file:
        folder: binary
      s3:
        bucket: your-bucket-name
        region: eu-west-3
        access_key: your-access-key
        secret_key: your-secret-key
        endpoint: https://s3.amazonaws.com  # Optional, for S3-compatible services
        prefix: "fluxcord"  # Optional, folder prefix in bucket
```

## Required Core Keys

The whole core configuration is read **once at startup** into `CoreSettings` (`fluxcord-core`, `core/config`).
Startup fails with a clear message when:

- `discord.token` is missing, blank or still `YOUR_BOT_TOKEN`
- `data.storage.type` / `data.binary.storage.type` is not one of the documented values (case-insensitive)
- `data.storage.type: DB` without a `jdbc:` URL, or `data.binary.storage.type: S3` with a missing bucket, region,
  access key or secret key (every missing key is listed)

An invalid `language.default` falls back to `en-US` with a warning. Relative storage folders are resolved against
the working directory. The bot never rewrites `config.yml` (a runtime change of the global prefix lasts until
restart; edit `commands.default-prefix` to make it permanent).

## Storage Configuration

### Data storage (`data.storage`)

- `type`: `FILE` or `DB`
- `DB` mode requires:
    - `data.storage.db.url`
    - `data.storage.db.username`
    - `data.storage.db.password`
- Optional `DB` keys:
    - `data.storage.db.max_pool_size` (default: `10`)
    - `data.storage.db.auto_commit` (default: `true`)

Supported database drivers (the SQL dialect is auto-detected from the JDBC URL):

- **MySQL / MariaDB** — `jdbc:mysql://host:3306/fluxcord` or `jdbc:mariadb://host:3306/fluxcord`
- **PostgreSQL** — `jdbc:postgresql://host:5432/fluxcord`

Both drivers are bundled; no extra dependency is required.

Optional file-storage keys:

- `data.storage.file.folder` (default: `data`)
- `data.storage.file.debounce_ms` (default: `2000`)

### Binary storage (`data.binary.storage`)

- `type`: `FILE` or `S3`

`S3` mode requires:

- `data.binary.storage.s3.bucket`
- `data.binary.storage.s3.region`
- `data.binary.storage.s3.access_key`
- `data.binary.storage.s3.secret_key`

Optional S3 keys:

- `data.binary.storage.s3.endpoint`
- `data.binary.storage.s3.prefix`

Optional file-storage key:

- `data.binary.storage.file.folder` (default: `binary`)

## Environment Variable Overrides

Core config reads environment variables with this mapping:

- Prefix: `FLUXCORD_`
- Path separator: `.` becomes `_`
- Example: `discord.token` -> `FLUXCORD_DISCORD_TOKEN`

Examples:

```bash
FLUXCORD_DISCORD_TOKEN=your_token_here
FLUXCORD_LANGUAGE_DEFAULT=fr-FR
FLUXCORD_DATA_STORAGE_TYPE=DB
FLUXCORD_DATA_STORAGE_DB_URL=jdbc:mariadb://db:3306/fluxcord
FLUXCORD_DATA_STORAGE_DB_USERNAME=fluxcord
FLUXCORD_DATA_STORAGE_DB_PASSWORD=secret
```

List overrides are split by `:`.

Note: keys containing `-` (for example `commands.default-prefix`) are currently difficult to override via environment
variables because the key name is mapped as-is except for dots.

## Configuration Versioning and Migration

### Core config

- Version key: `config_version`
- Current core version: `1`
- Legacy config (missing version / version `0`) is migrated to version `1`
- A backup is created before migration:
    - `config.yml.backup.<timestamp>`

### Plugin config

- Plugin config also uses `config_version`
- If a plugin provides migration logic (`ConfigurableMigrationPlugin`), Fluxcord runs it and updates the version
- Backup files are also created before plugin config migrations

## Docker Notes

- The compose file passes `FLUXCORD_DISCORD_TOKEN` to override `discord.token`
- You can also mount a custom `config.yml` at `/app/config.yml` if needed

## Troubleshooting

### Bot exits asking for token

Set one of the following:

- `discord.token` in `config.yml`
- `FLUXCORD_DISCORD_TOKEN` in environment

### Plugin config is not created

Check that the plugin JAR contains `config.yml` in its resources.
