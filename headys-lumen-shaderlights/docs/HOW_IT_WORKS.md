# How Heady's Lumen: ShaderLights works (a guide for reading and changing the code)

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
  custom `Item` that is not a block emits 0, so shaders see darkness. That is the original problem this mod fixes.
* **item id**: the shader pack ships a file `shaders/item.properties` like `item.10036 = torch campfire`. Iris reads
  it and builds a lookup table `item name -> number`. Items not listed get `-1`. The shader then does things like
  `if (heldItemId == 10036) colour = orange;`. A modded item is not in the file, so it gets `-1`.
* **colour**: Iris sends white `(1,1,1)` unless the item supplies its own colour (a mod can do that through Iris'
  `IrisItemLightProvider`). A pack only uses it if its GLSL reads `heldBlockLightColor`.

So the mod's whole job is: **right after Iris fills in those numbers, overwrite them for the items you care about**:
modded torches it detects, and every item in your list (vanilla ones included, e.g. a pink `minecraft:torch`).

---------------------------------------------------------------------------------------------------------------------

## 2. Data flow (follow one frame)

    every frame
      |
      v
    Iris: HeldItemSupplier.update()          computes lightValue, intID, lightColor for one hand
      |
      |   <-- our Mixin injects a call here (just before update() finishes)
      v
    IrisBridge.afterUpdate(supplier)
      |-- LightMatcher.matchFor(heldItem)    "is this one of ours? what level/colour/pretend?" -> Match
      |-- set lightValue                     (exact for your list, raise-only for auto-detected)
      |-- set lightColor                     (when the entry has a colour and "Send colour" is on)
      '-- applyItemId(...)                   choose heldItemId, see section 4
      |
      v
    Iris uploads the (now modified) numbers to the GPU  -->  shader pack uses them

Why one supplier per hand? Iris has two `HeldItemSupplier` objects, one for the main hand, one for the off-hand.
`update()` runs for both. Our hook sees which hand it is by reading the supplier's `hand` field.

**The second hook: how an item is drawn.** Some packs (Rethinking Voxels beta 9) ignore `heldItemId` and
instead turn the rendered hand into voxels: the held item glows if its *drawn* material is a light. Iris tells the
shader that material through another value, `currentRenderedItemId`, set each time an item is drawn (from
`block.properties` for block items such as torches, `item.properties` otherwise). So:

    Minecraft: ItemRenderer.render(item)
      |-- Iris (start of render): currentRenderedItemId = the pack's id for this item (0 / -1 if unknown)
      |-- ItemRendererMixin (first pushPose, i.e. after Iris): RenderedItemBridge.beforeRender(item)
      |      -> for your items: the id of the vanilla light it poses as (Pretend to be > closest colour > torch),
      |         or 0 for intensity-0 items
      '-- end of render: RenderedItemBridge.afterRender() puts 0 back, like Iris does

**Placed blocks.** A placed block reaches the shader through yet another number: the id Iris writes into the chunk
mesh from the pack's `block.properties` (the pack sees it as `mc_Entity`). A modded torch is not in that file, so
it gets `-1`: no coloured light, and packs that build a block world for their lighting (Kappa, KappaPT) treat it
as a solid cube. When Iris loads a pack it stores that map with `WorldRenderingSettings.setBlockStateIds`;
`PlacedLightsMixin` hands it a copy from `PlacedLights.augment` instead, with every state of your blocks added
under the id of the vanilla block it poses as. A block is matched through the item that places it, so a wall torch
follows its torch's entry, and becomes the vanilla *wall* torch, with its facing (and `lit`, `hanging`,
`waterlogged`) copied over. Saving rebuilds the copy from the pack's original map and reloads the chunks (like
F3+A), so already placed blocks update; nothing reloads if the result did not change. Intensity cannot apply to
placed blocks: their brightness is the block's own light level.

Setting: "Also change how the item is drawn" (`matchRenderedItem`). Side effect: your item also *looks* like
that vanilla light to the pack wherever it is drawn (in hand, dropped, in item frames).

---------------------------------------------------------------------------------------------------------------------

## 3. What each shader pack actually does (from reading their source)

Checked against the packs' public source on GitHub (Photon `2a3d502`, Rethinking Voxels `main` `1fd788f` and the
newer `singlesample-softshadows-v2` `d76c831`, Complementary Reimagined `e10d27d`, Solas `2ebf0cf`). Other versions may differ. Both Rethinking Voxels versions were also tested in game: GitHub `main` gives the exact
colour with working intensity, release beta 9 (Modrinth) gives the closest colour.

| Pack | Brightness of the held light | Colour of the held light | Where (in `shaders/`) |
|------|------------------------------|--------------------------|-----------------------|
| **Heady's Lumen: ShaderPack_PhotonFork** | the light level sets brightness **and** reach (for items outside Photon's table) | `heldBlockLightColor` when it is set; otherwise Photon's table | `include/lighting/handheld_lighting.glsl` |
| **Photon** | with coloured lights **on**: fixed per table entry, light level unused. Off: the light level | `heldItemId` 10032-10079 -> colour table. 10064-10079 are 16 colour slots for modded lights, listed under `simplylight:*` and `luminax:*` item names. Any other id: **no light** while coloured lights are on | `include/lighting/handheld_lighting.glsl`, colours in `include/lighting/lpv/light_colors.glsl` |
| **Rethinking Voxels**, release beta 9 (Complementary-style ids: torch = 44002) | from the light's material; the light level is not used | the hand is voxelized and glows if the **drawn** item's material (`currentRenderedItemId`) is a light; colour from that material. `heldItemId` is not used for this. Needs the pack's Dynamic Handheld Lighting | `program/shadow.glsl` |
| **Rethinking Voxels**, GitHub `main` (colour-coded ids: torch = 927) | its coloured held light has a fixed brightness (`10 x HELD_LIGHTING_MODE`); the light level is only used by its ordinary lighting | **the item id itself**, for ids 1-9999: `R = id % 16`, `G = (id >> 4) % 16`, `B = (id >> 8) % 16` (each x16). Any other id (e.g. `-1` for a modded item): no coloured held light | `program/prepare7_fsh.glsl` |
| **Complementary Reimagined** | `heldBlockLightValue` | with coloured lighting on: `heldItemId - 44000` -> colour table | `lib/lighting/heldLighting.glsl` |
| **Solas** | fixed (`DYNAMIC_HANDLIGHT_STRENGTH`); the light level is not read | `heldItemId` 1-97 -> colour table. Any other id: **no held light at all** | `lib/lighting/handlight.glsl` |

None of the four stock packs reads `heldBlockLightColor`; only the PhotonFork does. That is why the exact colour you
pick appears on the PhotonFork and on Rethinking Voxels `main` (through the id), while the other packs, Rethinking
Voxels beta 9 included, show the closest
colour their table offers.

Takeaways:
1. If a pack uses a **table** (Photon, Complementary, Solas) the best the mod can do is make the shader believe the
   item is one of the table's entries. It picks the entry whose colour is closest to the one you chose.
2. If a pack **decodes the id as a colour** (Rethinking Voxels `main`) the mod can send any colour (16 steps per
   channel), and dims it for a lower intensity.
2b. If a pack lights the hand from the **drawn** item (Rethinking Voxels beta 9), the mod draws your item as the
   closest-colour vanilla light (`RenderedItemBridge`).
3. If a pack **reads `heldBlockLightColor`** (the PhotonFork) the mod sends the exact colour and keeps the id out of
   the way.
4. Table packs give **no** light to ids they do not know (Photon with coloured lights on, Solas, and Rethinking
   Voxels' coloured light). That is why the mod gives unknown torches the pack's own torch id as a last resort.

---------------------------------------------------------------------------------------------------------------------

## 4. The decision order for the item id (`IrisBridge.applyItemId`)

Auto-detect (no list entry) works like a built-in "Pretend to be", most specific ending first (`LightMatcher.FAMILIES`):
`soul_torch`, `soul_lantern`, `redstone_torch`, `copper_torch` / `copper_lantern` (a green colour, since 1.20.1 has
no copper light), `sea_lantern`, `jack_o_lantern`, `lantern`, each at its vanilla level. Any other id ending in
`torch` poses as `minecraft:torch` (step 4). Your list always comes first.

Only runs for an item the mod matched in this hand. An item in your list with intensity **0** skips all of this:
its id is cleared to `-1` and its light set to 0, so no pack lights it (meant for unlit or burnt-out torches).
Otherwise, first:

    0. The pack already has an id for this item (e.g. minecraft:torch)?
       -> keep the pack's id, UNLESS the item is in your list with a colour or a "Pretend to be" (pink torch): then
          your settings win and the steps below decide.

Then, top to bottom, the first step that finds an id wins:

    1. "Pretend to be" is set and the pack knows that item         -> that item's id
    2. The entry has a colour and the pack decodes ids as colours  -> the colour encoded as an id (RV `main`)
    3. The entry has a colour, the pack uses a table               -> id of the item with the closest colour
       (skipped when the pack reads heldBlockLightColor: the colour is exact there already)
    4. Nothing found:
       - pack reads heldBlockLightColor and the entry has a colour -> id cleared to -1 (if the pack knew the item),
                                                                      so the pack uses the colour, not its table
       - the pack knew the item                                    -> keep its id
       - otherwise, if "Pretend to be a normal torch" is on        -> id of minecraft:torch

The light level is handled separately, before this: items in your list get exactly the level you set (so it can also
dim an item that glows by itself) and auto-detected torches only ever get brighter. One exception: Iris can fold the
off-hand's glow into the main-hand value ("old hand light"), so the main hand is not dimmed while the off-hand holds
something, or that glow would be lost.

"Closest colour" = `LightPalette.distance()`, a weighted difference of two colours after both are scaled to full
brightness (brightness is the intensity slider's job, not the colour's).

"Does this pack decode ids as colours?" = `IrisBridge.looksLikeRgbIds()`. It looks at the ids the pack gave to the
vanilla reference items it knows (torch, soul torch, redstone torch, ...). It needs at least 4 of them with ids in
1-4095, at least one id above 255, and 60% or more of them decoding to roughly that item's own colour. Table packs
fail that test (their ids are 10032+, 44000+, or 1-97). You can override it in the config (Auto / Always on / Off).

"Does this pack read heldBlockLightColor?" = `PackScanner`. When the pack changes it searches the pack's files for that
text, on a background thread.

---------------------------------------------------------------------------------------------------------------------

## 5. Where do I look? (file map)

    src/main/java/io/github/kook1023/lumen/
    |-- LumenShaderLights.java           entry point; loads config, registers commands, reloads on world join
    |-- command/LumenCommands.java       /lumen  (reload, addheld, status, config [1|2])
    |-- compat/ModMenuIntegration.java   the "Configure" button in Mod Menu
    |-- config/
    |   |-- LumenConfig.java             the JSON file. Fields = settings. Nested class Entry = one item row
    |   |-- LightMatcher.java            turns config into fast lookups; matchFor(stack) -> Match
    |   |-- Colors.java                  "#RRGGBB" <-> int, HSV <-> RGB
    |   |-- LightPalette.java            the list of vanilla "reference" light items and their colours
    |   '-- gui/
    |       |-- ClothConfigScreen.java   the in-game editor (tabs, rows, sliders)
    |       |-- ColorPreviewEntry.java   the live colour box
    |       |-- ConfigScreens.java       picks Cloth screen or "please install Cloth" screen
    |       '-- MissingClothScreen.java
    |-- iris/
    |   |-- IrisBridge.java              THE HEART. Everything that touches Iris' numbers
    |   |-- RgbId.java                   encode/decode the Rethinking Voxels `main` colour-in-id format
    |   |-- PackScanner.java             checks whether the active pack mentions heldBlockLightColor
    |   |-- RenderedItemBridge.java      the second hook: changes how your items are drawn (currentRenderedItemId)
    |   '-- PlacedLights.java            placed blocks: adds your blocks to the pack's block id map
    '-- mixin/
        |-- IrisHeldItemSupplierMixin.java         hooks Iris (new package name)
        |-- LegacyIrisHeldItemSupplierMixin.java   hooks Iris (old package name)
        |-- ItemRendererMixin.java                 hooks Minecraft's item renderer (for RenderedItemBridge)
        '-- PlacedLightsMixin.java                 hooks Iris' block id map (for PlacedLights)

    src/main/resources/
    |-- fabric.mod.json                  mod id, dependencies, entry points
    |-- headys_lumen.mixins.json         lists the mixin classes
    '-- assets/headys_lumen/lang/en_us.json   every piece of text in the GUI

---------------------------------------------------------------------------------------------------------------------

## 6. The Java ideas used, explained with the real code

### 6.1 Mixin: running our code inside someone else's class
Mixin lets a mod add code to a class it does not own. `IrisHeldItemSupplierMixin`:

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.uniforms.IdMapUniforms$HeldItemSupplier", remap = false)
    public class IrisHeldItemSupplierMixin {
        @Inject(method = "update", at = @At("TAIL"), remap = false, require = 0)
        private void headys_lumen$afterUpdate(CallbackInfo ci) {
            IrisBridge.afterUpdate(this);
        }
    }

* `targets = "..."` names the class as text, because we do not compile against Iris.
* `@Pseudo` means "if that class does not exist, quietly do nothing" (Iris not installed / other package name).
* `@Inject(... at = @At("TAIL"))` means "insert a call just before the method's **last** `return`". Iris' `update()`
  also returns early when there is no player or no item; our code does not run then, which is fine because there is
  nothing to light. (`@At("RETURN")` would run before every return.)
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

Fields with a unique type (`hand`, the item map, the colour) are found by type, the two `int` fields by name
(`intID`, `lightValue`), because there are two of them. That survives most changes between Iris versions. If a
needed field is missing, `resolve` throws, `afterUpdate` catches it, logs ONE error and switches itself off instead
of crashing the game.

The pack's item map is an `Object2IntFunction<NamespacedId>`. We do not know `NamespacedId`'s package, so
`resolve()` reads it from the field's *generic type* (`ParameterizedType`) and builds keys with its
`(String, String)` constructor.

### 6.3 Records
`record Match(int level, Vector3f color, ResourceLocation pretendTo, boolean exact)` is a tiny immutable data class
(`exact` = "from your list", which may override known items and dim). Java writes the constructor and getters
(`match.level()`) for you. Used for `Match`, `Rule`, `Handles`, `MappedRef`, `PackInfo`.

### 6.4 The "snapshot" pattern (LightMatcher)
The render thread asks "is this item one of ours?" every frame for both hands, and the config can change at any
moment (you press Save). So `LightMatcher` compiles the config into an immutable `Snapshot` object and publishes it with
`private static volatile Snapshot snapshot`. Readers grab the reference once and use it; a reload just swaps in a new
snapshot. No locks, no half-updated state. Results are cached per item in a `ConcurrentHashMap`.

### 6.5 Config file with Gson
`LumenConfig` is a plain class with public fields. Gson turns it into JSON and back using reflection, so adding a
setting is "add a field + give it a default value". Missing keys in an old file just keep the default.
`sanitize()` clamps numbers and cleans entries after loading. If the file is not valid JSON, the mod keeps the
settings it already had and logs a warning; it does not overwrite your file.

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

**Change the colour the mod assumes for a reference item**
Edit the hex in `LightPalette.REFS`. This only changes the mod's guess when it looks for the closest item; the pack
still uses its own colour for that item. Colours are compared ignoring brightness, so only hue and saturation matter.

**Add a new setting (example: a toggle called `foo`)**
1. `LumenConfig`: add `public boolean foo = true;`
2. `en_us.json`: add `headys_lumen.config.foo` and `headys_lumen.config.foo.tooltip`
3. `ClothConfigScreen.create`: copy one of the `startBooleanToggle(...)` blocks, change the names
4. Use it: `LumenConfig.get().foo` anywhere (for Iris logic, in `IrisBridge`)

**Teach the mod a new pack that encodes colours differently**
Work in `IrisBridge.applyItemId`. Add a detection function like `looksLikeRgbIds` and a new branch (and a small
helper like `RgbId` for the encoding). Read the pack's GLSL first: search for `heldItemId`.

**Make a pack recognise a mod item natively (no mod code)**
Open the shader zip, edit `shaders/item.properties`, and add your item to the line of the item it should look like,
e.g. change `item.10036 = torch campfire` to `item.10036 = torch campfire mymod:blue_torch`. The pack now knows it
and the mod keeps that id (step 0 of the decision order) unless your list gives the item a colour. You must redo
this whenever the pack updates, and only do it in your own copy (the pack's license applies).

**Find out why something does not work**
1. `/lumen status` while holding the item with a pack enabled. Read:
   * `Iris hook` should be `active`.
   * `Pack mode` tells you which strategy applies.
   * `Your items` lists what is in your config, separate from `Pack knows`.
   * `Sent to shader` is exactly what was written. If `light=0`, the mod did not match your item (check its id under
     `Your items`). `id=-1` with a light above 0 is normal on the PhotonFork. If the numbers look right but there is
     still no light, the **pack** is not using them.
2. `logs/latest.log`: search for `Lumen ShaderLights`. The mod logs when it hooks Iris and what the pack knows.
3. Hold a **vanilla torch**. If that is also dark in the pack, the pack's handheld lighting is off in its own
   settings.

**Testing without starting Minecraft**
The logic was tested with stand-in classes and a fake Iris supplier; those tests are not part of this repository.
The idea is worth copying: keep logic in plain classes (`LightMatcher`, `RgbId`, `Colors`, the decision code in
`IrisBridge`) so it can be tested on its own.

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
