package dev.shadertorches.iris;

import dev.shadertorches.ShaderTorches;
import dev.shadertorches.config.Colors;
import dev.shadertorches.config.LightPalette;
import dev.shadertorches.config.TorchConfig;
import dev.shadertorches.config.TorchMatcher;
import dev.shadertorches.config.TorchMatcher.Match;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import org.joml.Vector3f;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Talks to Iris. Iris computes these shader uniforms inside IdMapUniforms$HeldItemSupplier.update():
 *   heldBlockLightValue / heldBlockLightValue2  - light level (main hand / off-hand)
 *   heldBlockLightColor / heldBlockLightColor2  - light colour (Iris-specific, packs must opt in)
 *   heldItemId / heldItemId2                    - item id from the pack's item.properties
 * The mixins call {@link #afterUpdate} right after that method and we adjust the values for torch-like items.
 *
 * Colour reaches the shader two ways:
 *  1. heldBlockLightColor - exact colour, for packs that read it.
 *  2. heldItemId - for packs that colour lights per item: we pose as the item the pack knows whose colour is
 *     closest (or the one you picked).
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

    private static volatile Handles handles;
    private static volatile String failure;
    private static volatile WeakReference<Object> lastSupplier = new WeakReference<>(null);

    // Which reference items the current shader pack knows. Rebuilt when the pack (its item map) changes.
    private static volatile Object cachedFor;
    private static volatile List<MappedRef> cachedRefs = List.of();

    private IrisBridge() {
    }

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
            if (lastSupplier.get() != supplier) {
                lastSupplier = new WeakReference<>(supplier);
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

            int light = h.lightValue().getInt(supplier);
            int level = light;
            Match colorSource = TorchMatcher.NONE;
            if (own.level() > 0) {
                level = Math.max(level, own.level());
                colorSource = own;
            }
            if (off.level() > Math.max(light, own.level())) {
                level = off.level();
                colorSource = off;
            }
            if (level > light) {
                h.lightValue().setInt(supplier, level);
            }

            // 1. exact colour for packs that read heldBlockLightColor
            if (cfg.sendLightColor && h.lightColor() != null && colorSource.color() != null) {
                h.lightColor().set(supplier, new Vector3f(colorSource.color()));
            }
            // 2. item id for packs that colour by item
            if (own.level() > 0) {
                applyItemId(h, supplier, cfg, own);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            failure = e.toString();
            ShaderTorches.LOGGER.error("Could not hook Iris - custom torch lighting is disabled", e);
        }
    }

    /** Chooses which item the shader sees in this hand: the pack's own mapping > your pick > closest colour > torch. */
    private static void applyItemId(Handles h, Object supplier, TorchConfig cfg, Match own)
            throws ReflectiveOperationException {
        Object2IntFunction<Object> map = packMap(h, supplier);
        if (map == null || h.keyCtor() == null) {
            return;
        }
        int unmapped = map.defaultReturnValue();
        if (h.intId().getInt(supplier) != unmapped) {
            return; // the pack already has its own id for this item - respect that
        }
        int chosen = unmapped;
        if (own.pretendTo() != null) {
            chosen = lookup(h, map, own.pretendTo());
        }
        if (chosen == unmapped && own.color() != null && cfg.pickNearestColorItem) {
            chosen = nearestMapped(h, map, own.color(), unmapped);
        }
        if (chosen == unmapped && cfg.spoofTorchItemId && TORCH != null) {
            chosen = lookup(h, map, TORCH);
        }
        if (chosen != unmapped) {
            h.intId().setInt(supplier, chosen);
        }
    }

    private static int nearestMapped(Handles h, Object2IntFunction<Object> map, Vector3f color, int unmapped)
            throws ReflectiveOperationException {
        int target = (Math.round(color.x * 255f) << 16) | (Math.round(color.y * 255f) << 8) | Math.round(color.z * 255f);
        int best = unmapped;
        float bestDistance = Float.MAX_VALUE;
        for (MappedRef ref : mappedRefs(h, map, unmapped)) {
            float d = LightPalette.distance(target, ref.ref().rgb());
            if (d < bestDistance) {
                bestDistance = d;
                best = ref.packId();
            }
        }
        return best;
    }

    private static List<MappedRef> mappedRefs(Handles h, Object2IntFunction<Object> map, int unmapped)
            throws ReflectiveOperationException {
        if (cachedFor == map) {
            return cachedRefs;
        }
        List<MappedRef> list = new ArrayList<>();
        for (LightPalette.Ref ref : LightPalette.REFS) {
            ResourceLocation loc = ref.location();
            if (loc == null) {
                continue;
            }
            int id = lookup(h, map, loc);
            if (id != unmapped) {
                list.add(new MappedRef(ref, id));
            }
        }
        cachedRefs = list;
        cachedFor = map;
        ShaderTorches.LOGGER.info("Shader pack knows {} of {} reference light items", list.size(),
                LightPalette.REFS.size());
        return list;
    }

    private static int lookup(Handles h, Object2IntFunction<Object> map, ResourceLocation item)
            throws ReflectiveOperationException {
        return map.getInt(h.keyCtor().newInstance(item.getNamespace(), item.getPath()));
    }

    @SuppressWarnings("unchecked")
    private static Object2IntFunction<Object> packMap(Handles h, Object supplier) throws ReflectiveOperationException {
        return h.itemIdMap() == null ? null : (Object2IntFunction<Object>) h.itemIdMap().get(supplier);
    }

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

    /** For /shadertorches status: which of the reference items the current pack has an item id for. */
    public static String describePack() {
        Handles h = handles;
        Object supplier = lastSupplier.get();
        if (failure != null || h == null || supplier == null) {
            return "unknown (no shader pack running)";
        }
        try {
            Object2IntFunction<Object> map = packMap(h, supplier);
            if (map == null || h.keyCtor() == null) {
                return "unknown (this Iris build hides its item map)";
            }
            List<MappedRef> refs = mappedRefs(h, map, map.defaultReturnValue());
            if (refs.isEmpty()) {
                return "none - this pack does not colour held lights by item";
            }
            StringBuilder sb = new StringBuilder();
            for (MappedRef ref : refs) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(ref.ref().id().replace("minecraft:", "")).append(' ').append(Colors.toHex(ref.ref().rgb()));
            }
            return sb.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "error (" + e + ")";
        }
    }
}
