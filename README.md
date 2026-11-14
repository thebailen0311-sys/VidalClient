# Vanta

A Fabric client for Minecraft 1.21.11 focused on base hunting: it reads the world you already have loaded and tells
you when player-made structures are nearby, plus a combat and movement toolkit.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for **Minecraft 1.21.11** and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download `vanta-<version>.jar` from [`dist/`](dist/) (or from Releases, when one is published).
3. Drop it in `.minecraft/mods` and launch the Fabric 1.21.11 profile.

`bind` defaults to **Right Shift** for the GUI.

## Updating

The mod updates itself. On every launch it asks this repository for the newest version. When there is something newer
it downloads the jar into `mods/`, tells you in chat what changed, and removes the old jar on the next launch. Keep the
jar you installed once; you will not need to download again.

- Source of truth: [`update.json`](update.json) on `main`, or a GitHub **Release** (checked first, jar must be attached).
- Turn it off with `-Drefract.noupdate`.

## Publishing a new version

1. Bump `mod_version` in `gradle.properties`.
2. Run `./gradlew build` and copy `build/libs/refract-<version>+mc1.21.11.jar` to `dist/vanta-<version>.jar`.
3. Update `update.json` (`version`, `notes`, `jar`) and push.

Every installed copy picks it up on its next launch.

## Building

```
./gradlew build
```

JDK 21 is required. The jar lands in `build/libs/`.

## What is in it

- **Base Finder** — scans loaded chunks for player blocks, groups them into bases, and labels the type (Farm Stash,
  Trap Zone, Raid Base, Vault, Sus Tunnel, Griefed/Abandoned, Spawner Farm, Trading Hall, Storage Room, Redstone Farm,
  Living Quarters, Portal Hub, Hidden Room, Base). A lone chest is not a base; three or more containers are required.
- **Block ESP / Debris Finder** — outlines and traces storage, ores and Ancient Debris, with Ancient Debris and
  Debris + Ores presets, gradients and a line-of-sight filter.
- **Combat** — Trigger Bot, Auto Crystal, Anchor Macro, Auto Totem, Auto S Tap.
- **Utility** — Freecam, finding log, waypoints, notifications and a customisable HUD.

## License

MIT.
