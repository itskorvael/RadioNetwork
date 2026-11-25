# RadioNetwork

A radio system plugin for [Paper](https://papermc.io) (Minecraft 26.x, Java 25).

Stationary **Radio Terminals** (Lodestones) carry text and voice over named **frequencies**. Players listen with **Pocket Receivers**, and when things turn hostile they can **jam** an area with a Signal Jammer, or use a **Signal Scanner** to listen to encrypted frequencies and break the password in a code-breaking puzzle. Fuel, distance, relays, weather, night and lightning all change who hears what.

## Features

- Radio Terminals with fuel (redstone, glowstone, ender pearl), frequencies with owners, trusted players and passwords
- Text (`/broadcast`) and voice (optional [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat))
- Pocket Receivers (listen-only), with signal bars, depth, an Underground Antenna and an Earpiece
- Signal Repeaters (relays), Radio Jukeboxes (vanilla discs and player songs: note-block and MP3), Signal Cables
- Signal Jammers, Signal Scanners with the Decoder puzzle, encrypted frequencies
- Decommissioned terminals, jukeboxes, repeaters and jammers: scenery that looks the part and does nothing. Put a working item alone in a crafting grid, or `/radio give decommissioned_terminal` (also `_jukebox`, `_repeater`, `_jammer`)
- Local chat: ordinary chat only carries 32 blocks (configurable), so a radio is how you talk across a distance. `/whisper` (or `/w`) reaches 6 blocks, and people just outside see that something was said; `/shout` reaches 64, clear up close and breaking up toward its edge, with a cooldown, and from beyond talking range the speaker is only "a voice". Ops bypass it: `radionetwork.localchat.global` (heard by everyone in ordinary chat) and `radionetwork.localchat.spy` (hears everything) both default to op
- Weather and night effects, and lightning that knocks terminals offline and cuts cables
- One shared alert format across the plugin

Everything is configurable in `config.yml`, including every recipe.

## How it works

- **Terminals and fuel.** A terminal runs on fuel, which burns with time (1 second per second while it is working). Redstone gives 60 seconds and reaches up to 250 blocks, glowstone dust 120 seconds and 500 blocks, and an ender pearl 300 seconds with no range limit (it also crosses dimensions). The terminal's **Range** setting is how far it actually reaches, up to what its fuel allows.
- **Frequencies.** The first player to tune to a name owns it. The owner can make it open or closed to speak on, public or encrypted (a password), trust other players, transfer it, or release it. Frequencies nobody uses are released after a while.
- **Signal bars.** A listener hears a message as 1 to 4 bars, from distance, depth, weather and jamming. Weak signal drops words from text, and a Pocket Receiver shows its bars.
- **Relays.** A Signal Repeater linked to a terminal with Signal Cables passes on everything the terminal hears, up to three relays in a row by default.
- **Jammers.** A Signal Jammer linked to a terminal jams every channel within that terminal's radius (capped by `jammer.max-radius`, 150 by default), strongest in the middle and fading toward the edge. It warms up, jams, overheats and cools down, burns the terminal's fuel while it works, and burns out in a small blast if the terminal runs dry. The jam passes through relays, one bar weaker for each.
- **Scanners.** A Signal Scanner is listen-only. It hears an encrypted frequency as noise until the Decoder puzzle (opened by right-clicking it) is solved.
- **Announcements.** `/radio announce` and announcement signs send a free message on a channel through the nearest powered terminal on it, within `announcements.search-radius` blocks. [AutomatedTrains](https://github.com/itskorvael/AutomatedTrains) uses this for station announcements. Every sign that announces is logged (`/radio signs`).
- **Alerts.** Status messages share one format: a small coloured tag (Signal, Power, Access, Security, Jammer, Weather) and grey text. Only danger is red.

## Commands

| Command | What it does |
|---|---|
| `/broadcast <message>` (`/bc`) | Send a message through the terminal you are next to |
| `/whisper <message>` (`/w`) | Local chat, 6 blocks; people just outside see that you said something |
| `/shout <message>` | Local chat, 64 blocks, breaking up toward the edge |
| `/radio info`, `members`, `list`, `claim` | See and claim frequencies |
| `/radio mode <open\|closed>`, `access <public\|encrypted>` | Who may speak, who may join |
| `/radio trust`, `untrust`, `transfer`, `release` | Manage a frequency you own |
| `/radio jammer list` | What the jammer next to you is jamming |
| `/radio song ...` | Your note-block and MP3 songs |
| `/radio locate`, `signs`, `release`, `give`, `announce` | Admin tools |

Passwords are set from the terminal's Frequency menu, never typed in a command.

## Permissions

| Permission | Default | Allows |
|---|---|---|
| `radionetwork.use` | everyone | Radios, `/broadcast`, `/whisper`, `/shout` |
| `radionetwork.songs.add` | everyone | Adding songs from a link |
| `radionetwork.admin` | op | Configure any terminal and manage any frequency |
| `radionetwork.locate` | op | `/radio locate` and `/radio signs` |
| `radionetwork.announce` | op | `/radio announce` |
| `radionetwork.give` | op | `/radio give` |
| `radionetwork.localchat.global` | op | Ordinary chat reaches everyone |
| `radionetwork.localchat.spy` | op | Hear all chat, whispers and shouts |

## Building

Needs Java 25. The default build targets Minecraft 26.2.

```bash
./gradlew build
```

The jar is written to `build/libs/`. For Minecraft 26.1.2:

```bash
./gradlew build -PmcVersion=26.1.2 -PpaperApi=26.1.2.build.74-stable -PapiVersion=26.1
```

## Installing

Put the jar in your server's `plugins/` folder and restart. When updating, new settings are not added to an existing `config.yml` for you; copy them in from the new default config.

Simple Voice Chat is a soft dependency. Without it, text works and voice and MP3 songs do not.

## Tests

`tests/run-probes.sh` builds the plugin for 26.1.2 and runs it on a scratch Paper server with a probe plugin that checks about 290 things (garble, alerts, items, frequencies, fuel, relays, jammers, scanners, decommissioned items, local chat). It needs a Paper 26.1.2 server folder to copy and Java 25. See [tests/README.md](tests/README.md) for what is and is not covered. It drives the plugin's classes without players, so anything that needs a real client still has to be tried in game.

## License

All rights reserved. The source is public for reference only; see [LICENSE](LICENSE).
