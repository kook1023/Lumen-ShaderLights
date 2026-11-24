# For shader pack authors: how to support Heady's Lumen: ShaderLights (and any mod that colours held lights)

Heady's Lumen: ShaderLights lets players give custom items (from any mod) a held light with a chosen colour. Packs currently only
see `heldItemId` / `heldBlockLightValue`, which for modded items are `-1` / `0` unless the mod patches them. The mod
patches them, but the colour can only be *approximated* from your item table. Supporting one uniform makes the
colour exact.

## What to add (about 5 lines of GLSL)

Iris already provides these uniforms (Iris 1.6+ on 1.20.1):

    uniform int  heldBlockLightValue;   // 0-15, main hand
    uniform int  heldBlockLightValue2;  // 0-15, off-hand
    uniform vec3 heldBlockLightColor;   // main hand. Default (1,1,1) for every item
    uniform vec3 heldBlockLightColor2;  // off-hand

Suggested use:

    vec3 handLightColor(int id, vec3 irisColor) {
        if (id > 0) return yourTableColor(id);   // item you know about: keep your own colour
        return irisColor;                        // item you don't know: use what the mod/Iris says
    }

Notes:
* Iris sends white `(1,1,1)` for items that have no colour, so white is always a safe fallback.
* Keep honouring `heldBlockLightValue` for the *brightness*. Several packs currently ignore it, which makes
  intensity impossible to control for modded items.
* When Heady's Lumen: ShaderLights sees that your pack's source contains the text `heldBlockLightColor`, it switches to "exact
  colour" mode automatically: it sends the colour in the uniform and no longer changes `heldItemId` for coloured
  items. No other integration is needed.

## A working example
"Heady's Lumen: ShaderPack_PhotonFork" is Photon plus one patch (about 30 lines in `handheld_lighting.glsl`) that reads these uniforms. Unknown
items glow in the Iris-reported colour at their light level, known items keep the pack's own table colour.

## For mod authors (alternative)
Iris has a small API: an `Item` can implement `net.irisshaders.iris.api.v0.item.IrisItemLightProvider` and return its
own light level and colour. That gives the same uniforms without any config.

## Message you can copy

> Hi! I use a mod (Heady's Lumen: ShaderLights) that gives modded torches a coloured held light. Iris exposes
> `heldBlockLightColor` / `heldBlockLightColor2` (vec3) next to `heldBlockLightValue`. If your pack read that uniform
> for items it doesn't have an id for, modded items could show their exact colour instead of the closest table
> entry. It's about five lines in your handheld lighting code. Happy to test a build!
