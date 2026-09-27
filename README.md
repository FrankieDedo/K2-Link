# K2 Link

Small, read-only game connectors for [K2](https://github.com/FrankieDedo/K2) — the unofficial
Base Camp alternative for Mountain hardware. They let K2's game profiles light the keys of a
Mountain **DisplayPad** with the game's live state: health and hunger, the real hotbar icons,
dampeners and suit reserves, and so on.

The connectors never change the game. Key presses don't go through them either: K2 presses
**your own key bindings**, read from the game's config — so those keys work even without a
connector installed.

Each connector has its own release cadence, tagged `minecraft-vX.Y.Z` and
`space-engineers-vX.Y.Z`. Download from [Releases](https://github.com/FrankieDedo/K2-Link/releases).

## Minecraft: Java Edition

| | |
|---|---|
| Game version | 26.3 |
| Loader | [Fabric Loader](https://fabricmc.net/use/) 0.19+ (Fabric API is **not** needed) |
| Install | put `k2link-minecraft-<version>.jar` in the game's `mods` folder (or the instance's, for CurseForge / Prism) |
| Serves | `http://127.0.0.1:34874/state` (JSON) and `/hotbar/1` … `/hotbar/9` (PNG icons) |

Client-only: works in single-player and on any server.

## Space Engineers

| | |
|---|---|
| Kind | ModAPI mod (no plugin loader needed) |
| Install | single-player: copy the `K2Link` folder into `%AppData%\SpaceEngineers\Mods\`, then add **K2Link** to the world's mods. Dedicated servers: add the Workshop item (once published) to the server's mod list |
| Serves | mod scripts cannot open sockets, so it writes `state.json` to `%AppData%\SpaceEngineers\Storage\<mod>_K2Link\` four times a second |

Nothing runs on the server; the mod only reads what your own client already knows. Suit
broadcasting and on-foot altitude are not available to mods.

## Protocol

Every connector publishes ONE flat JSON object:

```json
{ "k2": 1, "modVersion": "1.0.0", "inGame": true, "health": 18, "maxHealth": 20, ... }
```

`inGame: false` means main menu / loading: every reading is unknown. `modVersion` lets K2 tell
when a newer connector is out.

## Building

- **Minecraft** — JDK 25, from `minecraft/`: `gradlew build` → `build/libs/`. On Windows, if
  Gradle fails with *"Unable to establish loopback connection"* (a JDK issue with long temp
  paths), set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\gt -Djava.io.tmpdir=C:\gt`
  (any short existing folder).
- **Space Engineers** — the mod ships as source; the game compiles it. `dotnet build` in
  `space-engineers/` only checks that it compiles (C# 6, against the game's DLLs;
  `-p:Bin64=<game>\Bin64` if the game is not found). It does not check the mod whitelist:
  keep to ModAPI types.

## License

MIT — see [LICENSE](LICENSE).
