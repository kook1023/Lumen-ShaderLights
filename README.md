# Heady's Lumen: ShaderLights

By **Headypeace**. A client-side Fabric mod for **Minecraft 1.20.1** that makes held **torch-like items from any mod**, and any vanilla
item you choose, give off **dynamic light in Iris shader packs**. Each item can get its own **colour** and
**intensity**. Nothing runs on the server and nothing changes Minecraft's own lighting: the mod only adjusts the
handheld-light values Iris passes to the shader pack.

Companion shader pack: **Heady's Lumen: ShaderPack_PhotonFork**, a small Photon fork that shows the exact colour and
intensity (see below).

## What it does
- Any **modded** item whose id ends in `torch` lights up automatically (toggle in the config).
- **Your Light items list**: per item an **intensity** (1-15), a **colour** (hue + saturation sliders with a live
  preview box) and an optional **"Pretend to be"** item.
- **Vanilla items can be overridden**: add e.g. `minecraft:torch` with a pink colour and your settings win over the
  shader pack's own torch colour.
- Intensity in your list is used exactly (it can also dim); auto-detected torches only ever get brighter.
- Held light only (main hand and off-hand), like shader packs themselves.

## How the colour reaches each shader pack
| Pack | What happens |
|---|---|
| **Heady's Lumen: ShaderPack_PhotonFork (unreleased)** | Reads Iris' `heldBlockLightColor`: your **exact colour**, and intensity sets brightness **and** reach. |
| **Rethinking Voxels** | Reads the item id *as a colour*: the mod sends your **exact colour** (16 steps per channel). Intensity dims the colour. |
| **Complementary** (Reimagined/Unbound) | Colour table by item id: the mod picks the item with the **closest colour**. Intensity works. |
| **Photon** | Colour table: **closest colour**, including Photon's 16 colour slots for modded lights. The pack ignores intensity when coloured lights are on. |
| **Solas** | Colour table: **closest colour**. The pack ignores intensity. |
| BSL / Bliss / Eclipse | Not inspected. Likely table-style: closest colour, or choose with "Pretend to be". |

The mod detects which approach a pack needs by itself. `/lumen status` shows what it found.

## Repository layout
    Lumen-ShaderLights/                 <- repo root
    |- .github/workflows/build.yml      builds the jar on every push
    |- README.md                        this file
    |- LICENSE                          MIT (covers the mod)
    '- headys-lumen-shaderlights/       the mod's Gradle project
       |- src/main/java/...             code (package io.github.kook1023.lumen)
       |- src/main/resources/...        fabric.mod.json, mixins, language file
       |- docs/HOW_IT_WORKS.md          guide to the code: how it works, where to look, how to change it
       |- docs/FOR_SHADER_AUTHORS.md    what a shader pack needs to add (about 5 lines of GLSL)
       '- build.gradle, gradle.properties, gradlew ...

The jar includes `LICENSE` from the repo root.

## Install
Put the jar in `mods/` together with **Fabric API** and **Iris** (+ Sodium). **Mod Menu** and **Cloth Config** are
optional; they give you the in-game editor.

## Build
Needs JDK 21 (or 17) to run Gradle; the mod targets Java 17. Uses Loom 1.7.4 / Gradle 8.10.2.

    cd headys-lumen-shaderlights
    ./gradlew build        # jar -> headys-lumen-shaderlights/build/libs/headys-lumen-shaderlights-1.3.0.jar
    ./gradlew runClient    # test game with Cloth Config + Mod Menu

GitHub builds it automatically too (`.github/workflows/build.yml`): every push and pull request uploads the jar as an
artifact, and pushing a tag like `v1.3.0` publishes it as a Release.

## Commands (client-side)
| Command | Does |
|---|---|
| `/lumen config` or `/lumen config 1` | open the config screen on the **General** tab |
| `/lumen config 2` | open it on the **Light items** tab |
| `/lumen addheld` | add the item in your main hand to your list (works instantly) |
| `/lumen reload` | re-read the config file |
| `/lumen status` | Iris hook state, how the current pack takes colours, **what the pack knows**, **your items** (listed separately), and exactly what was sent to the shader |

## Config
**In game:** Mod Menu -> Heady's Lumen: ShaderLights -> Configure, or `/lumen config`. On **Light items** press **+**
to add a row and **X** to remove one. Saving applies immediately.

**By file:** `config/headys_lumen.json`, then `/lumen reload` (or rejoin the world).

    {
      "enabled": true,
      "matchTorchSuffix": true,
      "defaultLightLevel": 14,
      "spoofTorchItemId": true,
      "sendLightColor": true,
      "pickNearestColorItem": true,
      "rgbIdMode": "AUTO",
      "items": [
        { "id": "minecraft:torch",       "level": 14, "color": "#FF8CD9", "pretendToBe": "" },
        { "id": "examplemod:blue_torch", "level": 12, "color": "#3399FF", "pretendToBe": "" },
        { "id": "examplemod:*_lamp",     "level": 10, "color": "",        "pretendToBe": "minecraft:sea_lantern" }
      ]
    }

- `id`: `modid:item`, `*` wildcards, or a bare `item` for any namespace. Vanilla ids work too.
- `level`: intensity 1-15, used exactly; `0` = leave the item alone.
- `color`: `#RRGGBB`, or `""` to let the pack decide.
- `pretendToBe`: any item id, or `""` for automatic.
- `rgbIdMode`: `AUTO`, `ON` or `OFF` (the Rethinking Voxels colour-in-id trick).

## Heady's Lumen: ShaderPack_PhotonFork
A fork of **Photon** by SixthSurge with one changed file (`shaders/include/lighting/handheld_lighting.glsl`) and
**coloured lights switched on by default**. It reads Iris' `heldBlockLightColor`, so modded and recoloured items show
the exact colour you pick, and their intensity controls brightness and reach. Items Photon already knows look exactly
like in Photon.

Drop the zip into `.minecraft/shaderpacks`. The file is called `Heady's Lumen - ShaderPack_PhotonFork.zip` because
file names cannot contain `:`.

**Sharing:** it contains Photon's code, so Photon's license applies (included in the zip). You may share it for free
(GitHub, Discord, direct download). You may **not** upload it to Modrinth or CurseForge without SixthSurge's written
permission, and you may not charge for it.

## Credits
- **Heady**: author.
- **Claude** (AI assistant by Anthropic): helped write the code and documentation.
- The companion shader pack is based on **Photon** by SixthSurge.

## Troubleshooting
Run `/lumen status` with a shader pack enabled while holding the item.
- `Iris hook: waiting` -> no pack has run yet; enable one.
- `Iris hook: FAILED (...)` -> your Iris version is laid out differently; check `logs/latest.log` for `Lumen ShaderLights`.
- `Sent to shader: main: id=-1 light=0` -> the item did not match (check its id under "Your items").
- `id` and `light` look right but there is no light -> the pack is not using them (its handheld lighting may be off).
- Colour is off but light works -> the pack only offers table colours; pick one with "Pretend to be", or use the
  PhotonFork pack.

## Notes
- **OptiFine** is not supported (it does not run on Fabric 1.20.1). Iris loads the same shader packs.
- Mod id `headys_lumen`, config file `config/headys_lumen.json`, Java package `io.github.kook1023.lumen`.
- The Iris hook is a mixin on `IdMapUniforms$HeldItemSupplier.update()` that reads Iris' private fields by reflection,
  so if Iris changes it switches itself off instead of crashing.
