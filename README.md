# Simple Voice Chat speaker system

A server-side Fabric mod that turns vanilla player heads into a voice-memo PA system on top of [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat). No client mod required — vanilla clients can join and use the system as long as the server has the mod installed.

## How it works

Two head skins, two roles:

- **🎤 Microphone** — what you tap to start (and finish) a recording.
- **🔊 Speaker** — what plays the voice memo back to anyone in earshot.

Both are vanilla `minecraft:player_head` blocks with fixed skin textures. There are no custom blocks, items, or block entities, so vanilla clients see standard heads and never hit decode errors. All metadata (UUIDs, labels, roles) lives in per-world `SavedData`.

## Quickstart

1. `/rspeaker give mic` and `/rspeaker give speaker` — get one of each.
2. Place a microphone where you want to talk from. Place a speaker (or several) where you want to be heard.
3. Right-click the microphone → a chest GUI titled "📻 Speakers" opens, listing every placed speaker.
4. Pick a target. The actionbar shows `● Recording → <target>` and a red dust indicator pulses above the mic.
5. Talk through Simple Voice Chat. Right-click any microphone again to send.
6. Each receiving speaker plays a chime, then your recording, then a tail-out click. Note particles drift up while it's transmitting.

## Labels and groups

`/rspeaker label <text>` while looking at a speaker assigns it a label. Speakers with the same label form a group: sending to one of them broadcasts to all of them, even across dimensions. The chest GUI collapses each group into a single entry with a stack-count badge (`kitchen ×3`).

Each label hashes to its own slight chime pitch, so different rooms are recognizable by ear before the voice plays.

## Commands

| Command | Description |
|---|---|
| `/rspeaker give mic` | Give yourself a microphone head. |
| `/rspeaker give speaker` | Give yourself a speaker head. |
| `/rspeaker label <text>` | Set the label of the speaker you're looking at. |
| `/rspeaker info` | Show role, label, and UUID of the speaker you're looking at. |
| `/rspeaker stop` | Finish the current recording (alternative to right-clicking a mic). |
| `/rspeaker cancel` | Discard the current recording without sending. |

All commands require gamemaster permission level (op level 2 by default).

## Idle / active feedback

- **Idle** — every placed unit emits one faint particle every ~5 s (end-rod sparks for mics, soft notes for speakers) so the system reads as "alive".
- **Recording** — red dust pulses above the source mic; actionbar refreshed every 2 s with the target label.
- **Receiving** — colored note particles drift from each speaker for the duration of the audio.

## Requirements

- Minecraft 26.1.2 (Java edition)
- Fabric Loader ≥ 0.19.0
- Fabric API
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) ≥ 2.6.0 on the server

Server-side only. Clients connect with vanilla.

## Building

```sh
./gradlew build
```

The output jar lands in `build/libs/`. Drop it into the server's `mods/` folder.

## License

MIT.
