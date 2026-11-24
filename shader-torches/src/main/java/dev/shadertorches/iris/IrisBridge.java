package dev.shadertorches.iris;

import dev.shadertorches.ShaderTorches;
import dev.shadertorches.config.Colors;
import dev.shadertorches.config.LightPalette;
import dev.shadertorches.config.TorchConfig;
import dev.shadertorches.config.TorchConfig.IdColorMode;
import dev.shadertorches.config.TorchMatcher;
import dev.shadertorches.config.TorchMatcher.Match;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import org.joml.Vector3f;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The heart of the mod. Talks to Iris.
 *
 * Iris computes these shader uniforms inside IdMapUniforms$HeldItemSupplier.update() (one supplier per hand):
 *   heldBlockLightValue  / heldBlockLightValue2   light level 0-15            (main hand / off-hand)
 *   heldBlockLightColor  / heldBlockLightColor2   light colour, Iris-only     (no pack reads it today)
 *   heldItemId           / heldItemId2            item id from the pack's item.properties
 * Our mixins call {@link #afterUpdate} right after update() and we adjust the values for torch-like items.
 *
 * How a colour reaches the shader (most exact first):
 *   A. heldBlockLightColor  - exact, but only packs that read it use it (checked by {@link PackScanner}).
 *   B. colour-in-item-id    - Rethinking Voxels decodes the id as a 12-bit colour, so we encode ours ({@link RgbId}).
 *   C. closest pack item    - packs with colour tables: we pose as the pack-known item with the nearest colour.
 *   D. your "Pretend to be" - you choose the item yourself (beats B and C).
 *   E. minecraft:torch      - last resort so the item at least glows.
 *
 * Iris' private fields are accessed with reflection (found by type/name) so this works with both the
 * net.coderbot.iris and net.irisshaders.iris package layouts. If anything looks different, the bridge disables
 * itself and logs one error instead of crashing.
 */
public final class IrisBridge {
    private static final ResourceLocation TORCH = ResourceLocation.tryParse("minecraft:torch");

    private record Handles(Field hand, Field intId, Field lightValue, Field lightColor, Field itemIdMap,
                           Field oldHandLight, Constructor<?> keyCtor) {
    }

    private record MappedRef(LightPalette.Ref ref, int packId) {
    }

    /** What we learned about the current shader pack's item ids. */
    private record PackInfo(List<MappedRef> refs, boolean rgbIds) {
    }

    private static volatile Handles handles;
    private static volatile String failure;

    private static volatile Object cachedFor;   // the pack's item map we built cachedInfo from
    private static volatile PackInfo cachedInfo = new PackInfo(List.of(), false);

    // What we last wrote into the uniforms, per hand (0 = main, 1 = off-hand). Shown by /shadertorches status.
    private static final int[] SENT_ID = {-1, -1};
    private static final int[] SENT_LIGHT = {0, 0};
    private static final int[] SENT_COLOR = {-1, -1};

    private IrisBridge() {
    }

    // ------------------------------------------------------------------------------------------------ main hook

    /** Called by the mixins at the end of HeldItemSupplier.update(). {@code supplier} is the Iris object. */
    public static void afterUpdate(Object supplier) {
        if (failure != null) {
            return;
        }
        TorchConfig cfg = TorchConfig.get();
        if (!cfg.enabled) {
            return;
        }
        try {
            Handles h = handles;
            if (h == null) {
                h = resolve(supplier.getClass());
                handles = h;
                ShaderTorches.LOGGER.info("Hooked Iris held-item lighting ({}) - colour uniform {}",
                        supplier.getClass().getName(), h.lightColor() != null ? "available" : "NOT available");
            }

            // Learn about the shader pack whenever it changes (its item map is a different object).
            Object2IntFunction<Object> map = packMap(h, supplier);
            if (map != null && map != cachedFor) {
                refreshPackInfo(h, map);
            }

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                return;
            }

            InteractionHand ownHand = (InteractionHand) h.hand().get(supplier);
            // With "old hand light" Iris folds the off-hand into the main-hand values, so we must too.
            boolean alsoOffhand = ownHand == InteractionHand.MAIN_HAND
                    && (h.oldHandLight() == null || h.oldHandLight().getBoolean(supplier));

            Match own = TorchMatcher.matchFor(player.getItemInHand(ownHand));
            Match off = alsoOffhand ? TorchMatcher.matchFor(player.getOffhandItem()) : TorchMatcher.NONE;

            // --- light level (heldBlockLightValue) ---
            int light = h.lightValue().getInt(supplier);
            int level = light;
            Match colorSource = TorchMatcher.NONE;
            if (own.level() > 0) {
                // Items from your list set the level exactly, so Intensity can also DIM a block item that glows by
                // itself. Auto-detected torches only ever raise it. With "old hand light" Iris has already folded
                // the off-hand's own glow into `light`, so we only dim when the off-hand is empty.
                boolean canDim = own.exact() && (!alsoOffhand || player.getOffhandItem().isEmpty());
                level = canDim ? own.level() : Math.max(level, own.level());
                colorSource = own;
            }
            if (off.level() > Math.max(light, own.level())) {
                level = off.level();
                colorSource = off;
            }
            if (level != light) {
                h.lightValue().setInt(supplier, level);
            }

            // --- A. exact colour uniform (heldBlockLightColor) ---
            if (cfg.sendLightColor && h.lightColor() != null && colorSource.color() != null) {
                h.lightColor().set(supplier, new Vector3f(colorSource.color()));
            }

            // --- B..E. item id (heldItemId) ---
            if (own.level() > 0) {
                applyItemId(h, supplier, cfg, own, map);
            }

            remember(h, supplier, ownHand);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failure = e.toString();
            ShaderTorches.LOGGER.error("Could not hook Iris - custom torch lighting is disabled", e);
        }
    }

    /** Decides which item id the shader sees in this hand. See the class comment for the order. */
    private static void applyItemId(Handles h, Object supplier, TorchConfig cfg, Match own,
                                    Object2IntFunction<Object> map) throws ReflectiveOperationException {
        boolean canLookUp = map != null && h.keyCtor() != null;
        int unmapped = map != null ? map.defaultReturnValue() : -1;
        if (h.intId().getInt(supplier) != unmapped) {
            return; // the pack already has its own id for this item - respect that
        }
        PackInfo info = cachedInfo;
        boolean rgbIds = cfg.rgbIdMode == IdColorMode.ON || (cfg.rgbIdMode == IdColorMode.AUTO && info.rgbIds());
        boolean exactViaUniform = cfg.sendLightColor && h.lightColor() != null && own.color() != null
                && PackScanner.readsColorUniform();

        int chosen = unmapped;
        // D. the item you picked under "Pretend to be"
        if (own.pretendTo() != null && canLookUp) {
            chosen = lookup(h, map, own.pretendTo());
        }
        if (chosen == unmapped && own.color() != null) {
            if (rgbIds) {
                // B. colour-in-id packs: dim the colour for a lower intensity (these packs ignore the light level)
                chosen = RgbId.encode(own.color(), Math.min(1f, own.level() / 14f));
            } else if (!exactViaUniform && cfg.pickNearestColorItem && canLookUp) {
                // C. closest pack-known item
                chosen = nearestMapped(info, own.color(), unmapped);
            }
        }
        // E. last resort
        if (chosen == unmapped && !exactViaUniform && cfg.spoofTorchItemId && canLookUp && TORCH != null) {
            chosen = lookup(h, map, TORCH);
        }
        if (chosen != unmapped) {
            h.intId().setInt(supplier, chosen);
        }
    }

    // ------------------------------------------------------------------------------------- pack knowledge

    private static void refreshPackInfo(Handles h, Object2IntFunction<Object> map) throws ReflectiveOperationException {
        List<MappedRef> refs = new ArrayList<>();
        if (h.keyCtor() != null) {
            int unmapped = map.defaultReturnValue();
            for (LightPalette.Ref ref : LightPalette.REFS) {
                ResourceLocation loc = ref.location();
                if (loc == null) {
                    continue;
                }
                int id = lookup(h, map, loc);
                if (id != unmapped) {
                    refs.add(new MappedRef(ref, id));
                }
            }
        }
        boolean rgbIds = looksLikeRgbIds(refs);
        cachedInfo = new PackInfo(List.copyOf(refs), rgbIds);
        cachedFor = map;
        ShaderTorches.LOGGER.info("Shader pack knows {} of {} reference light items; colour-in-item-id: {}",
                refs.size(), LightPalette.REFS.size(), rgbIds ? "detected" : "no");
        PackScanner.ensureScanned();
    }

    /**
     * Rethinking Voxels gives well-known items ids that decode (as 12-bit colours) to roughly the colour of that
     * item: torch -> orange, soul torch -> cyan, redstone torch -> red. Other packs use ids in tables
     * (10032+, 44000+, 1-97), which do not decode to those colours. We require several matches so one lucky
     * id cannot fool us, and an id above 255 so Solas' tiny ids (1-97) can never qualify.
     */
    private static boolean looksLikeRgbIds(List<MappedRef> refs) {
        int candidates = 0;
        int matches = 0;
        int maxId = 0;
        for (MappedRef r : refs) {
            int id = r.packId();
            if (id < 1 || id > 4095) {
                continue;
            }
            candidates++;
            maxId = Math.max(maxId, id);
            if (LightPalette.distance(RgbId.decode(id), r.ref().rgb()) < 0.2f) {
                matches++;
            }
        }
        return candidates >= 4 && maxId > 255 && matches * 10 >= candidates * 6;
    }

    private static int nearestMapped(PackInfo info, Vector3f color, int unmapped) {
        int target = (Math.round(color.x * 255f) << 16) | (Math.round(color.y * 255f) << 8) | Math.round(color.z * 255f);
        int best = unmapped;
        float bestDistance = Float.MAX_VALUE;
        for (MappedRef ref : info.refs()) {
            float d = LightPalette.distance(target, ref.ref().rgb());
            if (d < bestDistance) {
                bestDistance = d;
                best = ref.packId();
            }
        }
        return best;
    }

    private static int lookup(Handles h, Object2IntFunction<Object> map, ResourceLocation item)
            throws ReflectiveOperationException {
        return map.getInt(h.keyCtor().newInstance(item.getNamespace(), item.getPath()));
    }

    @SuppressWarnings("unchecked")
    private static Object2IntFunction<Object> packMap(Handles h, Object supplier) throws ReflectiveOperationException {
        return h.itemIdMap() == null ? null : (Object2IntFunction<Object>) h.itemIdMap().get(supplier);
    }

    // ----------------------------------------------------------------------------------- reflection setup

    private static Handles resolve(Class<?> type) throws ReflectiveOperationException {
        Field hand = null;
        Field intId = null;
        Field light = null;
        Field color = null;
        Field map = null;
        Field old = null;
        for (Field f : type.getDeclaredFields()) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (f.getType() == InteractionHand.class) {
                hand = f;
            } else if (f.getType() == int.class && name.equals("intid")) {
                intId = f;
            } else if (f.getType() == int.class && name.equals("lightvalue")) {
                light = f;
            } else if (f.getType() == Vector3f.class && (color == null || name.contains("color"))) {
                color = f;
            } else if (Object2IntFunction.class.isAssignableFrom(f.getType())) {
                map = f;
            } else if (f.getType() == boolean.class && name.contains("oldhandlight")) {
                old = f;
            }
        }
        if (hand == null || intId == null || light == null) {
            throw new NoSuchFieldException("Unexpected Iris HeldItemSupplier layout in " + type.getName());
        }
        open(hand);
        open(intId);
        open(light);
        open(color);
        open(map);
        open(old);

        // The map is Object2IntFunction<NamespacedId>; read NamespacedId from the generic type so we
        // do not need to know Iris' package name.
        Constructor<?> keyCtor = null;
        if (map != null
                && map.getGenericType() instanceof ParameterizedType pt
                && pt.getActualTypeArguments().length > 0
                && pt.getActualTypeArguments()[0] instanceof Class<?> keyType) {
            try {
                keyCtor = keyType.getDeclaredConstructor(String.class, String.class);
                keyCtor.setAccessible(true);
            } catch (NoSuchMethodException e) {
                keyCtor = null;
            }
        }
        return new Handles(hand, intId, light, color, map, old, keyCtor);
    }

    private static void open(Field f) {
        if (f != null) {
            f.setAccessible(true);
        }
    }

    // ------------------------------------------------------------------------------------------ diagnostics

    private static void remember(Handles h, Object supplier, InteractionHand hand) throws ReflectiveOperationException {
        int i = hand == InteractionHand.MAIN_HAND ? 0 : 1;
        SENT_ID[i] = h.intId().getInt(supplier);
        SENT_LIGHT[i] = h.lightValue().getInt(supplier);
        Object c = h.lightColor() == null ? null : h.lightColor().get(supplier);
        SENT_COLOR[i] = c instanceof Vector3f v
                ? (Math.round(v.x * 255f) << 16) | (Math.round(v.y * 255f) << 8) | Math.round(v.z * 255f)
                : -1;
    }

    /** For /shadertorches status. */
    public static String describeHook() {
        if (failure != null) {
            return "FAILED (" + failure + ")";
        }
        Handles h = handles;
        if (h == null) {
            return "waiting - no shader pack has run yet (is Iris installed and a pack selected?)";
        }
        return "active, heldBlockLightColor " + (h.lightColor() != null ? "available" : "NOT available in this Iris");
    }

    /** For /shadertorches status: how this pack takes colours. */
    public static String describePackMode() {
        if (handles == null) {
            return "unknown (no shader pack running)";
        }
        TorchConfig cfg = TorchConfig.get();
        boolean detected = cachedInfo.rgbIds();
        String mode = switch (cfg.rgbIdMode) {
            case ON -> "colour-in-item-id FORCED ON";
            case OFF -> "colour-in-item-id forced off";
            case AUTO -> detected ? "colour-in-item-id DETECTED (Rethinking Voxels style: exact colours)"
                    : "item colour table (colours are matched to the closest pack item)";
        };
        return mode + "; reads heldBlockLightColor: " + PackScanner.describe();
    }

    /** For /shadertorches status: which of the reference items the current pack has an item id for. */
    public static String describePackItems() {
        if (failure != null || handles == null || cachedFor == null) {
            return "unknown (no shader pack running)";
        }
        List<MappedRef> refs = cachedInfo.refs();
        if (refs.isEmpty()) {
            return "none - this pack does not colour held lights by item";
        }
        StringBuilder sb = new StringBuilder();
        for (MappedRef ref : refs) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ref.ref().id().replace("minecraft:", "")).append('=').append(ref.packId());
        }
        return sb.toString();
    }

    /** For /shadertorches status: exactly what we last put into the shader uniforms. */
    public static String describeSent() {
        if (handles == null) {
            return "nothing yet";
        }
        return "main: id=" + SENT_ID[0] + " light=" + SENT_LIGHT[0] + " colour=" + hex(SENT_COLOR[0])
                + " | off-hand: id=" + SENT_ID[1] + " light=" + SENT_LIGHT[1] + " colour=" + hex(SENT_COLOR[1]);
    }

    private static String hex(int rgb) {
        return rgb < 0 ? "n/a" : Colors.toHex(rgb);
    }
}
