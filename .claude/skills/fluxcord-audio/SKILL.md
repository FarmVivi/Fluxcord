---
name: fluxcord-audio
description: Expert on Fluxcord voice/audio - AudioService/AudioServiceImpl, per-guild AudioPipeline (multi-plugin send mixing, priority/bypass/fade via PriorityManager and AudioMixer, receive fan-out), JDA voice + DAVE E2EE through JDAVE natives, and how the music plugin (lavaplayer) plugs in. Use when touching voice connections, send/receive handlers, volume/priority, Opus vs PCM, DAVE/native errors, or the music plugin's player.
paths:
  - fluxcord-core/src/main/java/fr/farmvivi/fluxcord/core/audio/**
  - fluxcord-api/src/main/java/fr/farmvivi/fluxcord/api/audio/**
  - plugins/music-plugin/**
  - examples/plugins/plugin-example-audio/**
---

# Audio

## Contract (`api/audio/AudioService`)
`registerSendHandler(guild, plugin, AudioSendHandler, initialVolume 0-100?, priority 0-100)`, `deregisterSendHandler`, `setVolume`, `registerReceiveHandler(guild, plugin, AudioReceiveHandler)`, `deregisterReceiveHandler`, `setPriorityThreshold(guild, int)`, `hasActiveSendHandler`, `hasActiveReceiveHandler`, `closeAudioConnection(guild)`, `closeAllConnectionsForPlugin(plugin)`. Constants `MIN/MAX_VOLUME`, `MIN/MAX_PRIORITY`, `DEFAULT_PRIORITY_THRESHOLD` live on the interface — read them, don't hardcode. Events in `api/audio/events` (`AudioSendHandlerRegistered/Removed`, `AudioReceiveHandlerRegistered/Removed`, `AudioVolumeChanged`, `AudioFrameMixed`).

## Core (`core/audio`)
- `AudioServiceImpl` — `pipelines: guildId → AudioPipeline`, `pluginGuilds: plugin **name** → guildIds` (name, not id — inconsistency with the rest of the core). Creates the pipeline lazily, sets it as the guild's `AudioManager` send/receive handler, closes the voice connection when the pipeline becomes empty.
- `AudioPipeline` (~600 lines) — implements both JDA `AudioSendHandler` and `AudioReceiveHandler` for one guild. Every 20 ms JDA calls `canProvide()` then `provide20MsAudio()`. Strategy computed once per frame under `strategyLock`: if exactly one active source, **bypass** (its frames passed through, Opus allowed → `isOpus()` true); if several, all must be PCM and are mixed by `AudioMixer` with per-source volume × fade multiplier. Sources with `priority >= priorityThreshold` trigger fade-out of the others (`PriorityManager`, `FADE_STEPS`). Receive side fans `handleCombinedAudio` / `handleUserAudio` / `handleEncodedAudio` out to registered receive handlers.
- `AudioMixer` — sums 16-bit PCM stereo frames with clipping; `PriorityManager` — linear fade in/out multipliers per source name.
- Comments in this package are mostly French.

## Voice connection & DAVE
- JDA voice requires a DAVE implementation (mandatory since 2026-03-01). `core/discord/JDADiscordAPI.configureDaveSession()` sets `JDaveSessionFactory` from `club.minnced:jdave-*` (FFM, Java 25). Natives: `jdave-native-linux-x86-64`, `-linux-aarch64`, `-win-x86-64` in `fluxcord-core/pom.xml`; require glibc ≥ 2.38 → Ubuntu-based Docker images, not Alpine. `Enable-Native-Access: ALL-UNNAMED` is set in the jar manifest (shade + jar plugin).
- Failure symptom: `UnsatisfiedLinkError` / warning from `configureDaveSession` at boot; voice then fails to connect. Running on macOS: no native shipped → voice won't work locally.
- Joining a channel is done by the plugin (`guild.getAudioManager().openAudioConnection(channel)`); the core only owns the send/receive handler object.

## Music plugin (`plugins/music-plugin`)
`MusicPlugin` (permissions in `onPreEnable`, commands + JDA listeners in `onEnable`) → `MusicManager` (per-guild `MusicPlayer`) → `player/MusicPlayer` + `TrackScheduler` (lavaplayer `AudioPlayer`, queue, repeat) → `audio/AudioPlayerManager` (lavaplayer sources; youtube via `dev.lavalink.youtube`), `ui/MusicPlayerMessage` + `ButtonHandler` (component interactions). Lavaplayer is shaded and relocated (see the plugin pom) because the plugin classloader is child-first and lavaplayer isn't in `CORE_PACKAGES`. Its `AudioSendHandler` is registered on the core service with a priority so an announcement plugin can duck it.

## Testing
- Existing: `AudioMixerTest`, `PriorityManagerTest`, `AudioServiceImplTest`, `AudioPipelineTest` (13; mocks `Guild`/`AudioManager`, scripted `AudioSendHandler`s, reads the first big-endian sample of each frame to check levels). Covers bypass vs mix, Opus relay, PCM-over-Opus, high-priority Opus choice, fade-out/threshold, deregister, close.
- Real voice can only be checked with a smoke run + joining a channel (`/verify --smoke`, then a music `play` command).

## Improvement loop (mandatory — see /skill-maintenance)
Verify what you used against the code, fix or delete wrong lines, add dated **Learnings**, prune resolved **Known issues**. Keep < 300 lines.

## Learnings
- 2026-09-19: Initial audit. Volume range is read from `AudioService` constants — verify actual numbers before documenting them.
- 2026-09-20: Internal PCM convention is **little-endian** (docs/audio-api.md); `ensureBigEndianFrame` swaps at the JDA boundary, the mixer outputs LE. Fade fix: `updateFade` now runs before the multiplier is read, so ducking is audible on the first frame (was one frame late). Decision 2026-09-20: **bypass applies volume × fade during the LE→BE pass** (`ensureBigEndianFrame(le, gain)`, gain 1 = plain swap) and fades are stepped in bypass too, so `setVolume` works for a lone PCM source and the fade-in after an announcement is audible. Opus relay is untouched. Characterized: one PCM + one Opus active → PCM bypassed, Opus dropped for the frame.
- 2026-09-20 (A2): `SendStrategy.decide(states, threshold)` is the pure per-frame decision (silent / bypass source / mix list / ducking source = highest active priority ≥ threshold); `AudioPipeline` keeps fades, buffers and JDA glue. `AudioMixer.mix()` now outputs **big-endian** in one pass (its tests read BE). `AudioSettings` (`audio.fade-duration-ms`, `audio.ducking-level`, defaults 200 / 20) → `PriorityManager(fadeSteps, floor)`. `AudioFrameMixedEvent` is only built when `eventManager.hasListeners(...)`.
- 2026-09-20: music-plugin default volume 50 → 100 (`music.default_volume`, `MusicPlayer.DEFAULT_VOLUME`): lavaplayer passes Opus through untouched at 100 (any other value forces decode/scale/re-encode), like every mainstream bot. `MusicPlayer.setVolume` no longer mirrors the volume into `AudioService.setVolume` (the source is Opus so the core ignored it; with PCM it would have scaled twice).
- 2026-09-20: music-plugin autocomplete: `/play query` = recent tracks of the guild (`MusicManager.getRecentTracks`, filled in `trackLoaded`), `/remove position` = queue entries (integer choices), `/queue page`, `/volume level`, `/seek time` (quarters of the playing track). Providers use `MusicManager.findPlayer(guildId)` which never creates a player. `youtube-source` pinned to a master snapshot (see plan M1). Plugin shutdown: `musicManager.shutdown()` before `scheduler.shutdown()` and `MusicPlayerMessage.scheduleDelayedUpdate` tolerates a stopped scheduler (was a `RejectedExecutionException` on every shutdown).

## Known issues / open questions
- A1: `AudioServiceImpl` keys by `plugin.getName()` while `PluginManager` keys by id; `closeAllConnectionsForPlugin` therefore depends on names being unique. Switch to id (part of the identity chantier).
- `plugins/ai-audio-plugin` is a stub full of TODOs (speech recognition / TTS services do nothing) — decide with the user whether to keep it in the reactor.
