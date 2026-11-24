package io.github.kook1023.lumen.iris;

import io.github.kook1023.lumen.LumenShaderLights;
import io.github.kook1023.lumen.config.LightMatcher;
import io.github.kook1023.lumen.config.LightMatcher.Match;
import io.github.kook1023.lumen.config.LightPalette;
import io.github.kook1023.lumen.config.LumenConfig;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SolidBucketItem;
import org.joml.Vector3f;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

/**
 * The second hook: how an item is DRAWN.
 *
 * Some shader packs do not light the hand from {@code heldItemId} at all. The current releases of Rethinking Voxels,
 * for example, turn the rendered hand into voxels and treat it as a light source when the item being drawn has a
 * glowing material. That material comes from the Iris uniform {@code currentRenderedItemId}, which Iris sets each time
 * an item is drawn (from the pack's block.properties for block items such as torches, item.properties otherwise).
 * A modded torch is not in those files, so it is drawn as "nothing special" and never glows.
 *
 * Before one of your items is drawn, this class gives it the id Iris would use for the vanilla item it should look
 * like (your "Pretend to be", else the closest colour, else a normal torch), and puts the value back afterwards.
 * Called from {@code ItemRendererMixin}. Iris is accessed through reflection, like in {@link IrisBridge}.
 */
public final class RenderedItemBridge {
    private static final ResourceLocation TORCH = ResourceLocation.tryParse("minecraft:torch");

    private record Handles(Object settings, Method itemIds, Method blockStateIds,
                           Object state, Method getRendered, Method setRendered, Constructor<?> keyCtor) {
    }

    private record RenderRef(LightPalette.Ref ref, int renderId) {
    }

    private static volatile Handles handles;
    private static volatile String failure;
    private static volatile boolean ran;

    private static Object cachedFor;
    private static List<RenderRef> cachedRefs = List.of();

    // only touched on the render thread
    private static boolean swapped;
    private static String lastSwap = "";

    private RenderedItemBridge() {
    }

    /** Right before an item is drawn (after Iris has set its own id for it). */
    public static void beforeRender(ItemStack stack) {
        swapped = false;
        ran = true;
        LumenConfig cfg = LumenConfig.get();
        if (failure != null || !cfg.enabled || !cfg.matchRenderedItem || stack.isEmpty()) {
            return;
        }
        Match m = LightMatcher.matchFor(stack);
        boolean off = m.exact() && m.level() == 0;
        if (m.level() <= 0 && !off) {
            return; // not one of ours
        }
        try {
            Handles h = handles;
            if (h == null) {
                h = resolve();
                if (h == null) {
                    return; // Iris not installed
                }
                handles = h;
            }
            @SuppressWarnings("unchecked")
            Object2IntFunction<Object> itemIds = (Object2IntFunction<Object>) h.itemIds().invoke(h.settings());
            if (itemIds == null) {
                return; // no shader pack active
            }
            @SuppressWarnings("unchecked")
            Object2IntMap<Object> blockIds = (Object2IntMap<Object>) h.blockStateIds().invoke(h.settings());

            int target;
            if (off) {
                target = 0; // level 0: draw it as an ordinary, non-glowing item
            } else {
                int current = (int) h.getRendered().invoke(h.state());
                boolean known = current > 0;
                boolean override = m.exact() && (m.color() != null || m.pretendTo() != null);
                if (known && !override) {
                    return; // the pack already has its own look for this item
                }
                target = 0;
                if (m.pretendTo() != null) {
                    target = renderIdOf(h, itemIds, blockIds, m.pretendTo());
                }
                if (target <= 0 && m.color() != null && cfg.pickNearestColorItem) {
                    target = nearest(h, itemIds, blockIds, m.color());
                }
                if (target <= 0 && cfg.spoofTorchItemId && TORCH != null) {
                    target = renderIdOf(h, itemIds, blockIds, TORCH);
                }
                if (target <= 0) {
                    return;
                }
            }
            h.setRendered().invoke(h.state(), target);
            swapped = true;
            lastSwap = BuiltInRegistries.ITEM.getKey(stack.getItem()) + " drawn as id " + target;
        } catch (ReflectiveOperationException | RuntimeException e) {
            failure = e.toString();
            LumenShaderLights.LOGGER.error("Could not hook Iris item rendering - item look swapping is disabled", e);
        }
    }

    /** Right after the item was drawn: put the neutral value back, like Iris does. */
    public static void afterRender() {
        if (!swapped) {
            return;
        }
        swapped = false;
        Handles h = handles;
        if (h == null) {
            return;
        }
        try {
            h.setRendered().invoke(h.state(), 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failure = e.toString();
        }
    }

    /** The id Iris would set while drawing this vanilla item (same rule as Iris' own item renderer). */
    private static int renderIdOf(Handles h, Object2IntFunction<Object> itemIds, Object2IntMap<Object> blockIds,
                                  ResourceLocation id) throws ReflectiveOperationException {
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return 0;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item instanceof BlockItem blockItem && !(item instanceof SolidBucketItem)) {
            return blockIds == null ? 0 : blockIds.getOrDefault(blockItem.getBlock().defaultBlockState(), 0);
        }
        return h.keyCtor() == null ? 0 : itemIds.getInt(h.keyCtor().newInstance(id.getNamespace(), id.getPath()));
    }

    private static int nearest(Handles h, Object2IntFunction<Object> itemIds, Object2IntMap<Object> blockIds,
                               Vector3f color) throws ReflectiveOperationException {
        if (cachedFor != itemIds) {
            List<RenderRef> list = new ArrayList<>();
            for (LightPalette.Ref ref : LightPalette.REFS) {
                ResourceLocation loc = ref.location();
                int id = loc == null ? 0 : renderIdOf(h, itemIds, blockIds, loc);
                if (id > 0) {
                    list.add(new RenderRef(ref, id));
                }
            }
            cachedRefs = list;
            cachedFor = itemIds;
        }
        int target = (Math.round(color.x * 255f) << 16) | (Math.round(color.y * 255f) << 8) | Math.round(color.z * 255f);
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (RenderRef r : cachedRefs) {
            float d = LightPalette.distance(target, r.ref().rgb());
            if (d < bestDistance) {
                bestDistance = d;
                best = r.renderId();
            }
        }
        return best;
    }

    private static Handles resolve() throws ReflectiveOperationException {
        Class<?> settingsClass = firstClass("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings",
                "net.coderbot.iris.block_rendering.BlockRenderingSettings");
        Class<?> stateClass = firstClass("net.irisshaders.iris.uniforms.CapturedRenderingState",
                "net.coderbot.iris.uniforms.CapturedRenderingState");
        if (settingsClass == null || stateClass == null) {
            return null;
        }
        Object settings = settingsClass.getField("INSTANCE").get(null);
        Method itemIds = settingsClass.getMethod("getItemIds");
        Method blockStateIds = settingsClass.getMethod("getBlockStateIds");
        Object state = stateClass.getField("INSTANCE").get(null);
        Method getRendered = stateClass.getMethod("getCurrentRenderedItem");
        Method setRendered = stateClass.getMethod("setCurrentRenderedItem", int.class);

        // getItemIds() returns Object2IntFunction<NamespacedId>; read NamespacedId from the generic return type.
        Constructor<?> keyCtor = null;
        if (itemIds.getGenericReturnType() instanceof ParameterizedType pt
                && pt.getActualTypeArguments().length > 0
                && pt.getActualTypeArguments()[0] instanceof Class<?> keyType) {
            keyCtor = keyType.getDeclaredConstructor(String.class, String.class);
            keyCtor.setAccessible(true);
        }
        LumenShaderLights.LOGGER.info("Hooked Iris item rendering ({})", stateClass.getName());
        return new Handles(settings, itemIds, blockStateIds, state, getRendered, setRendered, keyCtor);
    }

    private static Class<?> firstClass(String... names) {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                // try the next package layout
            }
        }
        return null;
    }

    /** For /lumen status. */
    public static String describe() {
        if (!LumenConfig.get().matchRenderedItem) {
            return "off (setting)";
        }
        if (failure != null) {
            return "FAILED (" + failure + ")";
        }
        if (!ran) {
            return "not run yet";
        }
        return lastSwap.isEmpty() ? "active, nothing swapped yet" : "active, last: " + lastSwap;
    }
}
