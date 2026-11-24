# Shader Torches (Fabric 1.20.1)

Makes held **torch-like items from any mod** give off dynamic light in **Iris** shader packs, with a **custom colour
and intensity per item**. Client-side only. It feeds the shader's own handheld-light values.

New to the code? Read **docs/HOW_IT_WORKS.md**. Want a shader pack to support this properly? Send its author
**docs/FOR_SHADER_AUTHORS.md**.

## What it does
- Any **non-vanilla** item whose id ends in `torch` lights up (toggle in config).
- Your own items: **intensity**, **colour** (hue + saturation sliders with a live preview box) and an optional
  **"Pretend to be"** item.
- Held light only (main hand / off-hand). Never touches vanilla items. Items in your list get exactly the intensity you set
  (it can also dim an item that glows by itself); auto-detected torches only ever get brighter.

## How colour reaches each shader pack
| Pack (checked in its source) | What happens |
|---|---|
| **Rethinking Voxels** | Reads the item id *as a colour*. The mod detects this and sends your **exact colour** (16 steps per channel). Intensity dims the colour. |
| **Complementary** (Reimagined/Unbound) | Colour table by item id. The mod poses as the item with the **closest colour**. Intensity works. |
| **Photon** | Colour table by item id. **Closest colour**, now with real choices: recent Photon versions have 16 colour slots for modded lights (white, red, orange, yellow, lime, green, cyan, light blue, blue, purple, magenta, pink...), which the mod uses. Intensity is ignored by the pack when coloured lights are on. |
| **Solas** | Colour table (ids 1-97). **Closest colour**. Intensity ignored by the pack. |
| BSL / Bliss / Eclipse | Not inspected. Probably table-style: closest colour, or choose with "Pretend to be". |

No stock pack I checked reads Iris' `heldBlockLightColor` yet. **Lumen: Shaders** (a tiny Photon fork, shipped separately) does, as a testing ground and a reference for shader authors. The mod checks the active pack's source for that name and
switches to exact colours by itself if a pack ever does.

## Build
Needs JDK 21 (or 17) to *run Gradle*; the mod targets Java 17. Uses Loom 1.7.4 / Gradle 8.10.2.

    cd shader-torches
    ./gradlew build        # jar -> build/libs/shadertorches-1.2.1.jar
    ./gradlew runClient    # dev game with Cloth Config + Mod Menu

Put the jar in `mods/` with Fabric API and Iris (+ Sodium). Mod Menu + Cloth Config are optional (in-game editor).

## Config
**In game:** Mod Menu -> Shader Torches -> Configure, or `/shaderlights config` (General tab) /
`/shaderlights config2` (Light items tab). On **Light items** press **+** to add a row, **X** to remove one. Save
applies **immediately**.

**By file:** `config/shadertorches.json`, then `/shaderlights reload` (or rejoin the world).

    {
      "enabled": true,
      "matchTorchSuffix": true,
      "defaultLightLevel": 14,
      "spoofTorchItemId": true,
      "sendLightColor": true,
      "pickNearestColorItem": true,
      "rgbIdMode": "AUTO",
      "items": [
        { "id": "examplemod:blue_torch", "level": 14, "color": "#3399FF", "pretendToBe": "" },
        { "id": "examplemod:*_lamp",     "level": 12, "color": "",        "pretendToBe": "minecraft:sea_lantern" }
      ]
    }

- `id`: `modid:item`, `*` wildcards, or a bare `item` for any namespace.
- `level`: intensity 1-15, used exactly (it can also dim); `0` = leave the item alone.
- `color`: `#RRGGBB`, or `""` to let the pack decide.
- `pretendToBe`: any item id, or `""` for automatic.
- `rgbIdMode`: `AUTO`, `ON` or `OFF` (see Rethinking Voxels above).

## Commands (client-side; `/shadertorches` and `/shaderlights` are the same)
- `addheld` - add the item in your main hand (applies instantly)
- `reload` - re-read the JSON
- `config` / `config2` - open the config screen (General tab / Light items tab)
- `status` - Iris hook state, how the current pack takes colours, which items it knows, and **exactly what was sent
  to the shader** for each hand

## GitHub
`.github/workflows/build.yml` (next to this folder, at the repo root) builds on every push / PR and uploads the jar;
pushing a tag like `v1.2.0` also publishes a Release. Adjust `working-directory` and the artifact paths if your
folder is named differently.

## Troubleshooting
Run `/shaderlights status` holding the item, with a pack enabled.
- `Iris hook: waiting` -> no pack has run yet; enable one.
- `Iris hook: FAILED (...)` -> your Iris build is laid out differently; see `latest.log`.
- `Sent to shader: main: id=-1 light=0` -> the mod did not match the item (check the id / config).
- `id` and `light` look right but no light -> the pack is not using them (its own handheld-light setting may be off).
- Colour is wrong but light works -> the pack only offers table colours. Pick a better one under "Pretend to be".

## Notes
- **OptiFine** is not supported (it does not run on Fabric 1.20.1). Iris loads the same shader packs.
- The Iris hook is a mixin on `IdMapUniforms$HeldItemSupplier.update()` (both package names are covered) that reads
  Iris' private fields by reflection, so it fails soft.
