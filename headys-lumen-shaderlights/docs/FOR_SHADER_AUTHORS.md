# For shader pack authors: supporting Heady's Lumen: ShaderLights

Heady's Lumen: ShaderLights is a client-side Fabric mod (Minecraft 1.20.1, Iris) that lets players give held items a
light with a chosen **colour** and **intensity**: modded items, and also vanilla items they want to recolour (a pink
`minecraft:torch`, for example).

Shader packs normally learn what the player holds from two numbers: `heldItemId` (from your `item.properties`) and
`heldBlockLightValue`. Neither can carry an arbitrary colour, so without support from the pack the mod can only pick
the entry in your colour table that comes closest. Reading **one extra uniform** makes the colour exact, and honouring
the light level makes intensity work.

No dependency on the mod is needed: the uniforms below come from Iris itself, so supporting them also helps any mod
that gives its items a light colour through Iris.

---------------------------------------------------------------------------------------------------------------------

## 1. The uniforms (provided by Iris on 1.20.1)

    uniform int  heldItemId;            // id from your item.properties, -1 if not listed     (main hand)
    uniform int  heldItemId2;           //                                                     (off-hand)
    uniform int  heldBlockLightValue;   // 0-15                                                (main hand)
    uniform int  heldBlockLightValue2;  // 0-15                                                (off-hand)
    uniform vec3 heldBlockLightColor;   // light colour; white (1,1,1) unless something set it (main hand)
    uniform vec3 heldBlockLightColor2;  //                                                     (off-hand)

## 2. The one rule that matters

**If `heldBlockLightColor` is not white, use it, even when `heldItemId` is an item you know.**

Iris sends white for every item that has no colour of its own. A non-white value means the player (through the mod)
or another mod deliberately chose that colour, so it should win over your table. This is what makes recolouring
vanilla items work: a player who sets `minecraft:torch` to pink expects pink, not your torch orange.

    vec3 lumen_held_light(int item_id, int light_level, vec3 iris_color) {
        bool  custom_color = any(notEqual(iris_color, vec3(1.0)));
        float strength     = float(light_level) / 15.0;

        if (custom_color)            return iris_color * strength;   // someone chose a colour: it always wins
        if (your_table_has(item_id)) return your_table_color(item_id); // your own colour for items you know
        return your_default_held_color * strength;                     // unknown item, no colour: neutral light
    }

Call it once per hand (`heldItemId`/`heldBlockLightValue`/`heldBlockLightColor`, then the `...2` versions) and
combine the two results the way your pack already does, for example with `max()`.

## 3. Honour the light level

Players set an intensity from 1 to 15 per item, and the mod sends it **exactly**: it can also *dim* an item that
normally glows at 14 or 15. Many packs ignore `heldBlockLightValue` once coloured lights are on, which makes that
slider do nothing.

* Scale brightness by `heldBlockLightValue` at least for items that use the custom colour.
* Ideally scale the **reach** too. Brightness alone is largely hidden by tonemapping and auto-exposure; a light that
  also reaches less far is what players actually perceive as "dimmer". A simple way is to divide the position by a
  reach factor before your falloff:

      float reach   = mix(0.35, 1.0, float(light_level) / 15.0);   // level 15 = your normal reach
      float falloff = your_falloff(scene_pos / reach);

## 4. What the mod sends, case by case

So you can check your logic against real input. "Colour" is `heldBlockLightColor`; "id" is `heldItemId`.

| Player's setting for the held item | id | light value | colour |
|---|---|---|---|
| Item in their list **with a colour**, pack knows the item (e.g. pink `minecraft:torch`) | cleared to `-1` | their level | their colour |
| Item in their list **with a colour**, pack does not know it (modded item) | `-1` | their level | their colour |
| Item in their list with **only an intensity**, pack knows it | your id (kept) | their level | white |
| Item in their list with **intensity 0** ("no light", e.g. an unlit torch) | cleared to `-1` | `0` | untouched |
| Item with **"Pretend to be"** another item | that item's id | their level | their colour, or white if none |
| Modded item ending in `soul_torch`, `soul_lantern` or `redstone_torch`, auto-detected | your id for that vanilla item | 10, 10 or 7 | white |
| Any other modded item ending in `torch`, auto-detected, no colour | your `minecraft:torch` id | 14 (default) | white |
| Anything else | untouched (normal Iris behaviour) | untouched | untouched |

Separately, while one of these items is **drawn**, the mod sets `currentRenderedItemId` to the id your pack uses for
the vanilla light it poses as (or `0` for intensity 0). That is for packs that light the hand from the rendered
item's material rather than from `heldItemId`.

Tested in game with both Rethinking Voxels versions: GitHub `main` decodes `heldItemId` as a colour, so players get
the exact colour and intensity there; release beta 9 lights the hand from the drawn item's material, so players get
the closest vanilla light colour. Reading `heldBlockLightColor` (section 2) would give exact colours in any pack.

The id is cleared in the first row as a courtesy for packs that check the id first. Packs that follow the rule in
section 2 get the right result either way.

## 5. How the mod detects your support

When a shader pack loads, the mod searches its files for the text `heldBlockLightColor`.

* **Found:** it switches to "exact colour" mode for that pack. Coloured items are sent as in the table above, with no
  item-id tricks.
* **Not found:** it falls back to posing as the item in your `item.properties` whose colour is closest to the chosen
  one, so players still get *a* colour.

The search is plain text, so a comment counts too. Only mention `heldBlockLightColor` in your files if the pack
actually uses it. Players can check what the mod detected with `/lumen status`.

## 6. Working example

**Heady's Lumen: ShaderPack_PhotonFork** is Photon with one changed file,
`shaders/include/lighting/handheld_lighting.glsl` (about 60 lines; every change is marked `LUMEN:`). It reads the
uniforms above, scales brightness and reach by the light level for items outside Photon's table, and leaves items
Photon knows exactly as they were. It is Photon-licensed, so read it for reference rather than copying it into
another pack.

## 7. For mod authors

A mod that adds light-emitting items can give them a colour directly: implement Iris'
`net.irisshaders.iris.api.v0.item.IrisItemLightProvider` on the `Item` and return its light level and colour. Packs that
follow section 2 then show it correctly, with or without Heady's Lumen: ShaderLights installed.

---------------------------------------------------------------------------------------------------------------------

Questions or a pack that supports this? Open an issue at https://github.com/kook1023/Lumen-ShaderLights/issues.
