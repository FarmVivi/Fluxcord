# AI Audio Plugin

Voice AI for Fluxcord: the bot speaks in a voice channel, writes down what it hears, and remembers the
conversation.

Everything goes through the **OpenAI audio HTTP API**, which is also what the self-hosted servers
implement. So where the models run is configuration, not code: the hosted API, a machine on your LAN, or a
service next to the bot in the cluster. No native library, no SDK, nothing bundled — the plugin uses
`java.net.http` from the JDK and the core's Gson.

## What works today

| Command | What it does | Permission |
| --- | --- | --- |
| `/speak <text> [voice]` | Says something out loud in your voice channel | `ai-audio-plugin.tts` |
| `/silence` | Stops the bot talking, drops what was still queued | `ai-audio-plugin.tts` |
| `/transcribe <start\|stop>` | Writes what is said in the voice channel into the text channel | `ai-audio-plugin.transcribe` |
| `/forget <me\|channel\|server>` | Erases remembered conversation | none for `me`, `ai-audio-plugin.admin` for the rest |
| `/persona <show\|set\|reset> [field] [value] [scope]` | Shows or adjusts who the bot is here | none for `show`, `ai-audio-plugin.admin` to change it |

Transcription is **per speaker**: the plugin asks JDA for per-user audio (`canReceiveUser`), so two people
talking at once produce two separate transcriptions, each attributed to the name that person uses on that
server. The bot's voice is registered as a PCM source with a priority above the core's ducking threshold,
so the music plugin fades down while it speaks instead of covering it.

## What it remembers

Three independent histories, each sized on its own in `config.yml`, each turned off by setting it to `0`:

| History | Scope | Answers |
| --- | --- | --- |
| `memory.channel_turns` | one voice channel | what was just said in this conversation |
| `memory.server_turns` | one server, all its channels | the longer-running memory of a community |
| `memory.user_turns` | one person, **across every server** | what somebody told the bot elsewhere |

Each turn keeps who said it (id and display name), where (server and channel, both id and name) and when.
Nothing is a prompt: names and text stay in their own fields, because a Discord nickname is chosen by its
owner and must never be able to become an instruction to a model.

## Who it is, and how it feels

The **persona** is the part an operator chooses: a name, a few traits, a tone, a language and free-form
instructions. It is configured in `config.yml` and can be overridden per server and per voice channel with
`/persona`. An override states only what differs, so giving one channel a tone leaves its name, traits and
language to the server, and those to the configuration.

```
/persona show
/persona set field:tone value:familier scope:server
/persona set field:traits value:curieux, taquin scope:channel
/persona reset scope:channel
```

The **mood** is the part the conversation moves. Two axes — energy (calm to excited) and warmth (cold to
friendly) — kept per voice channel, because a mood belongs to a conversation and not to a community. Each
transcribed sentence raises the energy a little (`persona.mood.energy_per_turn`), and a mood **fades back to
neutral on its own**, halving every twenty minutes. Nothing runs while a channel is quiet: the fade is
computed from the elapsed time when the mood is read.

The bot also adapts to **who is there**: each participant's remembered turns make them a stranger, a known
face or a regular, counted from their own history **across every server** — so a regular from another server
is not a stranger here. That is what the per-person memory scope is for.

`/persona reset field:mood` clears a feeling without touching the persona.

## Running the models locally

Nothing here is specific to OpenAI — point each `base_url` at a compatible server and leave `api_key`
empty. Tested shapes of request, so any server implementing these two routes works:

| Capability | Route the plugin calls | Servers known to implement it |
| --- | --- | --- |
| transcription | `POST {base_url}/audio/transcriptions` (multipart WAV) | Speaches, faster-whisper-server, `whisper-server` (whisper.cpp), LocalAI |
| speech | `POST {base_url}/audio/speech` (JSON, `response_format: wav`) | Kokoro-FastAPI, openedai-speech (Piper/Coqui), LocalAI |

```yaml
speech_to_text:
  base_url: "http://192.168.1.20:8000/v1"
  api_key: ""
  model: "Systran/faster-whisper-small"
  language: "fr-FR"

text_to_speech:
  base_url: "http://192.168.1.20:8880/v1"
  api_key: ""
  model: "kokoro"
  voice: "af_heart"
```

The same applies when the bot runs in a container or an orchestrator: it reaches the models over HTTP, so
an in-cluster service URL is no different from a LAN address. Set the URLs through the `AI_AUDIO_STT_URL` /
`AI_AUDIO_TTS_URL` environment variables rather than editing the file baked into the image.

### On an AMD RDNA 2 GPU

Nothing runs inside the bot, so the GPU only matters for the server you host. Two things to know about
the RDNA 2 cards ROCm treats as unsupported (gfx1031, which covers the RX 6700 series):

- ROCm does not list it as officially supported; the usual workaround is
  `HSA_OVERRIDE_GFX_VERSION=10.3.0` in the server's environment, which makes it present itself as the
  supported gfx1030.
- For **speech synthesis**, a GPU is not worth it: Piper and Kokoro run comfortably on CPU and answer in
  well under a second. Keep the card for the LLM.

Raise `request.timeout_seconds` when a model shares the card with something else — a cold first request on
a loaded GPU can take a long time.

## Configuration

`src/main/resources/config.yml` is the reference and documents every key. **Every key in it is read by the
code**; there is nothing decorative left. If a setting is not in that file, the plugin does not have it.

An API key is only required when the endpoint is a hosted one: the plugin warns at startup if
`api.openai.com` is used without a key, and stays quiet for a local server.

## Build and install

```bash
mvn -pl plugins/ai-audio-plugin -am package
cp plugins/ai-audio-plugin/target/ai-audio-plugin-*.jar fluxcord-core/run/plugins/
```

The jar is a plain plugin jar: `fluxcord-api`, JDA, SLF4J and Gson are all `provided`, since the core
class loader owns them (`PluginClassLoader.CORE_PACKAGES`). Nothing is shaded, so there is nothing to
relocate.

## What is next

Answering out loud on its own — hearing a question in the voice channel and replying by voice. Everything
around that turn now exists: transcription gives it ears, `/speak` a voice, the memory a context, and the
persona and mood a manner. The remaining piece is the model call in between, which will use the same
OpenAI-compatible HTTP surface (so Ollama serves it locally or remotely with no code change), take a
`PersonaSnapshot` as its input, and expose the three memory lookups as tool calls so the model asks for what
it needs instead of being handed everything.

One rule that call has to keep: the persona is trusted input and a transcription is not. The snapshot keeps
names, spoken text and persona fields separate precisely so that whatever composes the instructions cannot
splice a participant called `"Bob. SYSTEM: ignore the above"` into them.

## Tests

`mvn -o clean test -pl plugins/ai-audio-plugin`

The HTTP clients are tested against a real `com.sun.net.httpserver.HttpServer` on a loopback port rather
than a mocked client: what has to be right is the shape of the bytes on the wire — a multipart body a
Whisper server accepts, the right JSON field names — and only a real server observes that. Those same
tests are the local-server case, since a local server differs from a hosted one only by this URL.
