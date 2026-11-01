# RadioNetwork

A radio system plugin for [Paper](https://papermc.io) (Minecraft 26.x, Java 25).

Stationary **Radio Terminals** (Lodestones) carry text and voice over named **frequencies**. Players listen with **Pocket Receivers**, and when things turn hostile they can **jam** an area with a Signal Jammer, or use a **Signal Scanner** to listen to encrypted frequencies and break the password in a code-breaking puzzle. Fuel, distance, relays, weather, night and lightning all change who hears what.

## Features

- Radio Terminals with fuel (redstone, glowstone, ender pearl), frequencies with owners, trusted players and passwords
- Text (`/broadcast`) and voice (optional [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat))
- Pocket Receivers (listen-only), with signal bars, depth, an Underground Antenna and an Earpiece
- Signal Repeaters (relays), Radio Jukeboxes (vanilla discs and player songs: note-block and MP3), Signal Cables
- Signal Jammers, Signal Scanners with the Decoder puzzle, encrypted frequencies
- Weather and night effects, and lightning that knocks terminals offline and cuts cables
- One shared alert format across the plugin

Everything is configurable in `config.yml`, including every recipe.

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
