# Shader Torches (Fabric 1.20.1)

Makes held **torch-like items from any mod** give off dynamic light in **Iris** shader packs, with a **custom
colour and intensity per item**. Client-side only - no server mod, no custom lighting engine. It feeds the shader's
own handheld-light values.

## What it does
- Any **non-vanilla** item whose id ends in `torch` (so `lantern_torch`, `somemod:torch`, ...) lights up. Toggle in config.
- Add your own items, each with **intensity**, **colour** (hue + saturation sliders) and an optional **"pretend to be"** item.
- Held light only (main hand / off-hand), same as vanilla shader packs.
- Never touches vanilla items (Iris already handles those) and never *lowers* light an item already gives.

## How colour reaches the shader
Shader packs get handheld light colour in one of two ways, and the mod does both:

1. **`heldBlockLightColor`** - an Iris value holding the exact colour. Packs that read it show your exact colour
   (the pack's "coloured lighting" option must be on). Controlled by *Send colour to shader*.
2. **By item** - most packs colour handheld light per item (via `item.properties`). For those, the shader is told
   the item is **whichever item the pack knows with the closest colour** (*Match closest item*), or the item you
   chose under **Pretend to be**. If the pack knows none of them, it falls back to `minecraft:torch`.

The built-in colours for the "closest item" search are typical values (e.g. torch = orange, soul torch = cyan);
every pack defines its own, so it is a good guess, not exact. Run `/shadertorches status` to see which reference
items your current pack knows.

## Build
Needs JDK 21 (or 17) to *run Gradle*; the mod itself targets Java 17.

    cd shader-torches
    ./gradlew build        # jar -> build/libs/shadertorches-1.1.0.jar
    ./gradlew runClient    # dev game with Cloth Config + Mod Menu

Put the jar in `mods/` with Fabric API and Iris (+ Sodium). Mod Menu + Cloth Config are optional (they give you the
in-game editor).

## Config
**In game:** Mod Menu -> Shader Torches -> Configure, or `/shadertorches config`. On the **Light items** tab press **+**
to add a row, **X** to remove one. Save applies **immediately** - no restart, no rejoin.

**By file:** `config/shadertorches.json`, then `/shadertorches reload` (or just rejoin the world).

    {
      "enabled": true,
      "matchTorchSuffix": true,
      "defaultLightLevel": 14,
      "spoofTorchItemId": true,
      "sendLightColor": true,
      "pickNearestColorItem": true,
      "items": [
        { "id": "examplemod:blue_torch", "level": 14, "color": "#3399FF", "pretendToBe": "" },
        { "id": "examplemod:*_lamp",     "level": 12, "color": "",        "pretendToBe": "minecraft:sea_lantern" }
      ]
    }

- `id`: `modid:item`, `*` wildcards, or a bare `item` for any namespace.
- `level`: intensity 0-15 (raises light only; `0` = leave the item alone).
- `color`: `#RRGGBB`, or `""` to let the pack decide.
- `pretendToBe`: any item id, or `""` for automatic.

## Commands (client-side)
- `/shadertorches addheld` - add the item in your main hand (applies instantly)
- `/shadertorches reload` - re-read the JSON
- `/shadertorches status` - Iris hook state, which reference items the pack knows, what your held items resolve to
- `/shadertorches config` - open the config screen

## GitHub
`.github/workflows/build.yml` sits next to this folder (repo root). It builds on every push / PR and uploads the jar as
an artifact; pushing a tag like `v1.1.0` also publishes a Release.

    repo/
    |- .github/workflows/build.yml
    '- shader-torches/          <- this folder

## Troubleshooting
Run `/shadertorches status` with a shader pack enabled.
- `Iris hook: waiting` -> no shader pack has run yet; enable one (and make sure Iris is installed).
- `Iris hook: FAILED (...)` -> your Iris build is laid out differently from what this expects; see `latest.log`.
- `active` but no light -> that pack may not use handheld light, or has it disabled in its own settings.
- Light works but colour doesn't -> the pack neither reads `heldBlockLightColor` nor knows any reference item
  (`Pack knows: none`). Pick a "Pretend to be" item the pack uses, or use a pack with coloured handheld light.

## Notes
- **OptiFine** is not supported (it does not run on Fabric 1.20.1). Iris loads the same shader packs.
- The Iris hook is a mixin on `IdMapUniforms$HeldItemSupplier.update()` (both the `net.irisshaders` and the older
  `net.coderbot` package names are covered), reading Iris' private fields reflectively so it fails soft.
