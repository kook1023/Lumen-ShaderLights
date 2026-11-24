# How Shader Torches works (a guide for reading and changing the code)

This is written for someone who wants to understand the mod well enough to edit it. Read top to bottom once, then use
the "Where do I look?" and "Recipes" sections as a reference.

---------------------------------------------------------------------------------------------------------------------

## 1. The problem in one picture

A shader pack is a pile of GLSL programs that run on your GPU. They know nothing about Minecraft items. The only
things they learn about what you are holding are **uniforms**: small values Iris uploads to the GPU every frame.

    heldBlockLightValue   int   0-15   how bright the held item is           (main hand)
    heldBlockLightValue2  int   0-15                                          (off-hand)
    heldItemId            int         a number the PACK chose for the item    (main hand)
    heldItemId2           int                                                 (off-hand)
    heldBlockLightColor   vec3        a colour (Iris-only extra)              (main hand)
    heldBlockLightColor2  vec3                                                (off-hand)

Where do those numbers come from?

* **light value**: Iris asks the item "how much do you emit?". For block items it is the block's light level. A
  custom `Item` that is not a block emits 0, so shaders see darkness. That is the original bug this mod fixes.
* **item id**: the shader pack ships a file `shaders/item.properties` like `item.10036 = torch campfire`. Iris reads
  it and builds a lookup table `item name -> number`. Items not listed get `-1`. The shader then does things like
  `if (heldItemId == 10036) colour = orange;`. A modded item is not in the file, so it gets `-1`.
* **colour**: Iris always sends white `(1,1,1)`. Packs would have to opt in to read it.

So the mod's whole job is: **right after Iris fills in those numbers, overwrite them for our items.**

---------------------------------------------------------------------------------------------------------------------

## 2. Data flow (follow one frame)

    every frame
      |
      v
    Iris: HeldItemSupplier.update()          computes lightValue, intID, lightColor for one hand
      |
      |   <-- our Mixin injects a call here (end of update)
      v
    IrisBridge.afterUpdate(supplier)
      |-- TorchMatcher.matchFor(heldItem)    "is this a torch? what level/colour/pretend?"   -> Match
      |-- set lightValue                     (exact for your list, raise-only for auto-detected)
      |-- set lightColor                     (exact colour uniform)
      '-- applyItemId(...)                   choose heldItemId, see section 4
      |
      v
    Iris uploads the (now modified) numbers to the GPU  -->  shader pack uses them

Why one supplier per hand? Iris has two `HeldItemSupplier` objects, one for the main hand, one for the off-hand.
`update()` runs for both. Our hook sees which hand it is by reading the supplier's `hand` field.

---------------------------------------------------------------------------------------------------------------------

## 3. What each shader pack actually does (from reading their source)

I read the public source of these packs on GitHub (main branch). Your installed version may differ slightly.

| Pack | Brightness comes from | Colour comes from | Notes |
|------|-----------------------|-------------------|-------|
| **Rethinking Voxels** | fixed (`10 x HELD_LIGHTING_MODE`), light level ignored | **the item id itself**: ids 1-9999 are decoded as `R = id%16, G = (id/16)%16, B = (id/256)%16` | `program/prepare7_fsh.glsl`. Exact colours possible (16 steps/channel). |
| **Complementary Reimagined** (Unbound shares the code) | `heldBlockLightValue` | `heldItemId - 44000` -> colour table | `lib/lighting/heldLighting.glsl`. Only when "coloured lighting" is on. |
| **Photon** | ignored when coloured lights are on | `heldItemId` 10032-10079 -> table (`include/lighting/lpv/light_colors.glsl`). 10064-10079 are 16 colour slots for modded lights, keyed by `simplylight:*` item names | `include/lighting/handheld_lighting.glsl` |
| **Solas** | fixed strength setting | `heldItemId` 1-97 -> table | `lib/lighting/handlight.glsl` |
| BSL, Bliss, Eclipse | not inspected | | closed or not downloadable from here |

None of them reads `heldBlockLightColor`. (The test pack **Lumen: Shaders**, a one-file Photon fork, does. Its diff is a good example of how little a pack needs to change: `lumen-handheld.patch`.) That is why exact colours only work on Rethinking Voxels, and why
everything else "picks a nearby item".

Takeaways:
1. If a pack uses a **table** (Photon, Complementary, Solas) the best we can do is make the shader believe the item
   is one of the table's entries. We choose the entry whose colour is closest to what you want.
2. If a pack **decodes the id as a colour** (Rethinking Voxels) we can send any colour.
3. If a pack **reads `heldBlockLightColor`** (none yet) we send the exact colour and leave the id alone.

---------------------------------------------------------------------------------------------------------------------

## 4. The decision order for the item id (`IrisBridge.applyItemId`)

    1. The pack already has an id for this exact item?            -> leave it (the pack knows best)
    2. You set "Pretend to be" on the entry?                      -> use that item's id          (option 3)
    3. Entry has a colour and the pack decodes ids as colours?    -> encode the colour as an id  (RV, exact)
    4. Entry has a colour, pack uses tables?                      -> id of the closest-colour item (option 2)
    5. Otherwise (and "pretend to be a torch" is on)              -> id of minecraft:torch
    (If the pack reads heldBlockLightColor, steps 3-5 are skipped for coloured entries: the exact colour is used.)

"Closest colour" = `LightPalette.distance()`, a weighted difference of two colours after both are scaled to full
brightness (brightness is the intensity slider's job, not the colour's).

"Does this pack decode ids as colours?" = `IrisBridge.looksLikeRgbIds()`. It looks at the ids the pack gave to
well-known items. If torch / lantern / soul torch / redstone torch all decode to roughly their own colours, it is a
colour-in-id pack. Table packs never decode to the right colours, so they are never fooled. You can override it in
the config (Auto / Always on / Off).

---------------------------------------------------------------------------------------------------------------------

## 5. Where do I look? (file map)

    src/main/java/dev/shadertorches/
    |-- ShaderTorches.java               entry point; loads config, registers commands, reloads on world join
    |-- command/TorchCommands.java       /shadertorches and /shaderlights  (reload, addheld, status, config, config2)
    |-- compat/ModMenuIntegration.java   the "Configure" button in Mod Menu
    |-- config/
    |   |-- TorchConfig.java             the JSON file. Fields = settings. Nested class Entry = one item row
    |   |-- TorchMatcher.java            turns config into fast lookups; matchFor(stack) -> Match
    |   |-- Colors.java                  "#RRGGBB" <-> int, HSV <-> RGB
    |   |-- LightPalette.java            the list of vanilla "reference" light items and their colours
    |   '-- gui/
    |       |-- ClothConfigScreen.java   the in-game editor (tabs, rows, sliders)
    |       |-- ColorPreviewEntry.java   the live colour box
    |       |-- ConfigScreens.java       picks Cloth screen or "please install Cloth" screen
    |       '-- MissingClothScreen.java
    |-- iris/
    |   |-- IrisBridge.java              THE HEART. Everything that touches Iris' numbers
    |   |-- RgbId.java                   encode/decode the Rethinking Voxels colour-in-id format
    |   '-- PackScanner.java             checks whether the active pack mentions heldBlockLightColor
    '-- mixin/
        |-- IrisHeldItemSupplierMixin.java         hooks Iris (new package name)
        '-- LegacyIrisHeldItemSupplierMixin.java   hooks Iris (old package name)

    src/main/resources/
    |-- fabric.mod.json                  mod id, dependencies, entry points
    |-- shadertorches.mixins.json        lists the mixin classes
    '-- assets/shadertorches/lang/en_us.json   every piece of text in the GUI

---------------------------------------------------------------------------------------------------------------------

## 6. The Java ideas used, explained with the real code

### 6.1 Mixin: running our code inside someone else's class
Mixin lets a mod add code to a class it does not own. `IrisHeldItemSupplierMixin`:

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.uniforms.IdMapUniforms$HeldItemSupplier", remap = false)
    public class IrisHeldItemSupplierMixin {
        @Inject(method = "update", at = @At("TAIL"), remap = false, require = 0)
        private void shadertorches$afterUpdate(CallbackInfo ci) {
            IrisBridge.afterUpdate(this);
        }
    }

* `targets = "..."` names the class as text, because we do not compile against Iris.
* `@Pseudo` means "if that class does not exist, quietly do nothing" (Iris not installed / other package name).
* `@Inject(... at = @At("TAIL"))` means "insert a call at the end of the method `update`". TAIL covers early
  `return`s too.
* `remap = false` because Iris names are not Minecraft names and need no translation.
* `require = 0` means "if you cannot find the method, do not crash".
* Inside the injected method `this` is really the Iris object, so we hand it to `IrisBridge` as a plain `Object`.

There are two mixin classes only because Iris renamed its package (`net.coderbot.iris` -> `net.irisshaders.iris`).

### 6.2 Reflection: touching private fields we cannot compile against
We never import Iris classes. `IrisBridge.resolve()` loops over the supplier's fields and finds them by **type or
name**:

    if (f.getType() == InteractionHand.class)            hand = f;
    else if (f.getType() == int.class && name.equals("intid")) intId = f;
    ...
    f.setAccessible(true);                  // allow reading/writing a private field
    h.lightValue().setInt(supplier, 14);    // write into Iris' field

Finding by type rather than by exact name is deliberate: it survives small renames between Iris versions. If a
needed field is missing, `resolve` throws, `afterUpdate` catches it, logs ONE error and switches itself off instead
of crashing the game.

The pack's item map is an `Object2IntFunction<NamespacedId>`. We do not know `NamespacedId`'s package, so
`resolve()` reads it from the field's *generic type* (`ParameterizedType`) and builds keys with its
`(String, String)` constructor.

### 6.3 Records
`record Match(int level, Vector3f color, ResourceLocation pretendTo)` is a tiny immutable data class. Java writes the
constructor and getters (`match.level()`) for you. Used for `Match`, `Rule`, `Handles`, `MappedRef`, `PackInfo`.

### 6.4 The "snapshot" pattern (TorchMatcher)
The render thread asks "is this item a torch?" thousands of times per second, and the config can change at any
moment. So `TorchMatcher` compiles the config into an immutable `Snapshot` object and publishes it with
`private static volatile Snapshot snapshot`. Readers grab the reference once and use it; a reload just swaps in a new
snapshot. No locks, no half-updated state. Results are cached per item in a `ConcurrentHashMap`.

### 6.5 Config file with Gson
`TorchConfig` is a plain class with public fields. Gson turns it into JSON and back using reflection, so adding a
setting is "add a field + give it a default value". Missing keys in an old file just keep the default.
`sanitize()` clamps numbers and cleans entries after loading.

### 6.6 Cloth Config GUI
`ClothConfigScreen` builds the screen with Cloth's builder API: tabs are `ConfigCategory`, each row is an entry.
The item list is a `NestedListListEntry` (a list whose rows are `MultiElementListEntry` groups). Cloth gives it the
"+" and "X" buttons for free. Our `Row` class keeps references to each widget so `toEntry()` can read them back when
you press Save. `ColorPreviewEntry` is a custom widget: it overrides `render()` and asks for its colour every frame.
The whole Cloth part is only loaded when Cloth is installed (`ConfigScreens` checks `isModLoaded`).

---------------------------------------------------------------------------------------------------------------------

## 7. Recipes

**Add a new reference item to the "closest colour" search**
Add a line to `LightPalette.REFS`: `new Ref("minecraft:some_item", 0xRRGGBB)`. It is also in the dropdown
automatically. It only matters on packs that list that item in `item.properties`.

**Change the colour a pack "sees" for an item (when guessing)**
Edit the hex in `LightPalette.REFS`. Colours are matched ignoring brightness, so only the hue/saturation matter.

**Add a new setting (example: a toggle called `foo`)**
1. `TorchConfig`: add `public boolean foo = true;`
2. `en_us.json`: add `shadertorches.config.foo` and `shadertorches.config.foo.tooltip`
3. `ClothConfigScreen.create`: copy one of the `startBooleanToggle(...)` blocks, change the names
4. Use it: `TorchConfig.get().foo` anywhere (for Iris logic, in `IrisBridge`)

**Teach the mod a new pack that encodes colours differently**
Work in `IrisBridge.applyItemId`. Add a detection function like `looksLikeRgbIds` and a new branch (and a small
helper like `RgbId` for the encoding). Read the pack's GLSL first: search for `heldItemId`.

**Make a pack recognise a mod item natively (no mod code)**
Open the shader zip, edit `shaders/item.properties`, and add your item to the line of the item it should look like,
e.g. change `item.10036 = torch campfire` to `item.10036 = torch campfire mymod:blue_torch`. The pack now knows it
and the mod steps aside (step 1 of the decision order). You must redo this when the pack updates.

**Find out why something does not work**
1. `/shaderlights status` while holding the item with a pack enabled. Read:
   * `Iris hook` should be `active`.
   * `Pack mode` tells you which strategy applies.
   * `Sent to shader` is exactly what we wrote. If `id=-1` and `light=0`, the mod did not match your item (check the
     item id / config). If `id` and `light` look right but there is still no light, the **pack** is not using them.
2. `logs/latest.log`: search for `Shader Torches`. The mod logs when it hooks Iris and what the pack knows.
3. Hold a **vanilla torch**. If that is also dark in the pack, the pack's handheld lighting is off in its own
   settings.

**Run the tests I used**
They live outside the mod (a handful of stub classes + a fake Iris supplier). The idea is worth copying: pull logic
into plain methods (`TorchMatcher`, `RgbId`, `Colors`, the decision code in `IrisBridge`) so it can be tested without
starting Minecraft.

---------------------------------------------------------------------------------------------------------------------

## 8. Glossary

* **Uniform**: a value the game uploads to the GPU each frame for shaders to read.
* **item.properties**: a file in a shader pack mapping item names to numbers.
* **Iris**: the mod that runs shader packs on Fabric.
* **Mixin**: a way to inject code into another class at load time.
* **Reflection**: reading/writing fields and calling methods by name at runtime.
* **Mojang mappings**: the readable names (`InteractionHand`, `ItemStack`) this project is written against.
* **Loom**: the Gradle plugin that sets up a Fabric dev environment and builds the jar.
* **Cloth Config / Mod Menu**: optional mods that provide the in-game config screen and the Configure button.
