package io.github.kook1023.lumen.iris;

import io.github.kook1023.lumen.LumenShaderLights;
import io.github.kook1023.lumen.config.LightMatcher;
import io.github.kook1023.lumen.config.LightMatcher.Match;
import io.github.kook1023.lumen.config.LightPalette;
import io.github.kook1023.lumen.config.LumenConfig;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Placed lights: makes the shader pack treat a placed modded torch / lantern as the vanilla light it poses as.
 *
 * Shader packs recognise a placed block by the id Iris gives it from the pack's block.properties, written into the
 * chunk mesh. A modded torch is not in that file, so it gets -1: no coloured light, and some packs (Kappa, KappaPT)
 * treat it as a solid cube that casts a full block of shadow.
 *
 * When Iris loads a pack it hands its block id map to {@code WorldRenderingSettings.setBlockStateIds}. The mixin
 * {@code PlacedLightsMixin} passes that map through {@link #augment}, which returns a copy with your blocks added,
 * each under the id of the vanilla block it poses as. The same rules as held items decide what that is: your list
 * ("Pretend to be", else the closest colour), then auto-detect (soul torch, lantern, ...). Matching goes through the
 * block's ITEM, so a wall torch follows the entry of the torch item that places it.
 *
 * When you change settings, {@link #refresh} builds a fresh copy from the pack's original map and reloads the world
 * renderer (like F3+A), so blocks that are already placed update too.
 */
public final class PlacedLights {
    /** Vanilla standing torches and the wall block they place on a wall. */
    private static final Map<String, String> WALL_SIBLINGS = Map.of(
            "minecraft:torch", "minecraft:wall_torch",
            "minecraft:soul_torch", "minecraft:soul_wall_torch",
            "minecraft:redstone_torch", "minecraft:redstone_wall_torch");

    private static volatile Object2IntMap<BlockState> original;   // the pack's own map, as Iris gave it
    private static volatile Object2IntMap<BlockState> lastAugmented;
    private static volatile int lastAdded;
    private static volatile int lastCoded;
    private static volatile String failure;

    private PlacedLights() {
    }

    // ------------------------------------------------------------------------------------------- the main step

    /** Called by the mixin with the map Iris is about to store. Returns the map Iris should store instead. */
    public static Object2IntMap<BlockState> augment(Object2IntMap<BlockState> incoming) {
        if (incoming == null || incoming == lastAugmented) {
            return incoming; // nothing loaded, or our own map coming back through refresh()
        }
        original = incoming;
        try {
            Object2IntMap<BlockState> out = build(incoming);
            lastAugmented = out;
            return out;
        } catch (RuntimeException | LinkageError e) {
            failure = e.toString();
            LumenShaderLights.LOGGER.error("Placed lights: could not add blocks to the shader's block ids", e);
            return incoming;
        }
    }

    /**
     * Re-applies after a settings change and rebuilds the world's meshes, but only if the result changed (a rebuild
     * is a short hitch, like F3+A). Call on the render thread.
     */
    public static void refresh() {
        Object2IntMap<BlockState> base = original;
        if (base == null) {
            return; // no shader pack loaded
        }
        try {
            Object settings = settingsInstance();
            if (settings == null) {
                return;
            }
            Object2IntMap<BlockState> out = build(base);
            if (out.equals(lastAugmented)) {
                return; // nothing that placed blocks use has changed
            }
            lastAugmented = out;
            Method set = settings.getClass().getMethod("setBlockStateIds", Object2IntMap.class);
            set.invoke(settings, out);
            Minecraft mc = Minecraft.getInstance();
            if (mc.levelRenderer != null && mc.level != null) {
                mc.levelRenderer.allChanged(); // like F3+A: placed blocks pick up the new ids
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            failure = e.toString();
            LumenShaderLights.LOGGER.error("Placed lights: could not refresh", e);
        }
    }

    // --------------------------------------------------------------------------------------- building the copy

    static Object2IntMap<BlockState> build(Object2IntMap<BlockState> pack) {
        Object2IntOpenHashMap<BlockState> out = new Object2IntOpenHashMap<>(pack);
        out.defaultReturnValue(pack.defaultReturnValue());
        LumenConfig cfg = LumenConfig.get();
        int added = 0;
        codedCount = 0;
        if (cfg.enabled && cfg.placedLights) {
            List<Ref> refs = knownRefs(pack);
            boolean colorIds = PackScanner.packUnderstandsColorIds();
            for (Block block : BuiltInRegistries.BLOCK) {
                added += addBlock(block, pack, out, refs, cfg, colorIds);
            }
        }
        lastAdded = added;
        lastCoded = codedCount;
        LumenShaderLights.LOGGER.info("Placed lights: {} block states given a light id", added);
        return out;
    }

    private record Ref(Block block, int rgb) {
    }

    /** Reference lights whose block the pack has an id for (for "closest colour"). */
    private static List<Ref> knownRefs(Object2IntMap<BlockState> pack) {
        List<Ref> refs = new ArrayList<>();
        for (LightPalette.Ref r : LightPalette.REFS) {
            Block b = vanillaBlock(r.location());
            if (b != null && pack.getOrDefault(b.defaultBlockState(), -1) >= 0) {
                refs.add(new Ref(b, r.rgb()));
            }
        }
        return refs;
    }

    private static int codedCount;

    private static int addBlock(Block block, Object2IntMap<BlockState> pack, Object2IntMap<BlockState> out,
                                List<Ref> refs, LumenConfig cfg, boolean colorIds) {
        Item item = block.asItem();
        if (item == null || item == Items.AIR) {
            return 0;
        }
        Match m = LightMatcher.matchFor(new ItemStack(item));
        if (m.level() <= 0) {
            return 0; // not one of ours, or intensity 0 ("no light"): leave the block as the pack sees it
        }
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        boolean vanilla = "minecraft".equals(blockId.getNamespace());
        boolean override = m.exact() && (m.color() != null || m.pretendTo() != null);
        if (vanilla && !(override && cfg.placedRecolorVanilla)) {
            return 0; // the pack already knows vanilla blocks; only change them when your list asks for it
        }
        boolean packKnows = pack.getOrDefault(block.defaultBlockState(), -1) >= 0;
        if (packKnows && !override) {
            return 0; // the pack has its own id for this modded block: keep it
        }

        Block target = targetFor(m, refs);
        if (target == null) {
            return 0;
        }
        // A wall torch is placed by the same item as the standing one, but is its own block.
        boolean isWall = item instanceof BlockItem bi && bi.getBlock() != block;
        if (isWall) {
            Block wall = vanillaBlock(ResourceLocation.tryParse(
                    WALL_SIBLINGS.getOrDefault(BuiltInRegistries.BLOCK.getKey(target).toString(), "")));
            if (wall != null) {
                target = wall;
            }
        }

        int added = 0;
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            BlockState like = copyProperties(state, target.defaultBlockState());
            int id = pack.getOrDefault(like, -1);
            // id -1: the pack has no id for that exact state, e.g. it lists only "redstone_torch:lit=true",
            // so an unlit modded state stays as it was
            if (id >= 0) {
                if (colorIds && m.color() != null) {
                    // The pack (PhotonFork) understands colour-coded ids: exact colour and intensity
                    id = PlacedColorId.encode(m.color(), m.level());
                    codedCount++;
                }
                out.put(state, id);
                added++;
            }
        }
        return added;
    }

    /** The vanilla block to pose as: your "Pretend to be", else the closest colour, else a torch. */
    private static Block targetFor(Match m, List<Ref> refs) {
        if (m.pretendTo() != null) {
            Block b = vanillaBlock(m.pretendTo());
            if (b != null) {
                return b;
            }
        }
        if (m.color() != null && LumenConfig.get().pickNearestColorItem && !refs.isEmpty()) {
            Vector3f c = m.color();
            int rgb = (Math.round(c.x * 255f) << 16) | (Math.round(c.y * 255f) << 8) | Math.round(c.z * 255f);
            Ref best = null;
            float bestDistance = Float.MAX_VALUE;
            for (Ref r : refs) {
                float d = LightPalette.distance(rgb, r.rgb());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = r;
                }
            }
            return best.block();
        }
        return LumenConfig.get().spoofTorchItemId ? vanillaBlock(ResourceLocation.tryParse("minecraft:torch")) : null;
    }

    /** The block a vanilla item id places (sea_lantern -> sea lantern block), or null if it is not a block item. */
    private static Block vanillaBlock(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        if (BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.get(id) instanceof BlockItem bi) {
            return bi.getBlock();
        }
        if (BuiltInRegistries.BLOCK.containsKey(id)) {
            return BuiltInRegistries.BLOCK.get(id);
        }
        return null;
    }

    /** Copies same-named properties (facing, lit, hanging, waterlogged) from the modded state onto the vanilla one. */
    private static BlockState copyProperties(BlockState from, BlockState to) {
        BlockState result = to;
        for (Property<?> target : to.getProperties()) {
            for (Property<?> source : from.getProperties()) {
                if (source.getName().equals(target.getName()) && source.getValueClass() == target.getValueClass()) {
                    result = setSame(result, target, from.getValue(source));
                }
            }
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState setSame(BlockState state, Property<T> property, Object value) {
        T typed = property.getValueClass().cast(value);
        return property.getPossibleValues().contains(typed) ? state.setValue(property, typed) : state;
    }

    private static Object settingsInstance() throws ReflectiveOperationException {
        for (String name : new String[] {"net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings",
                "net.coderbot.iris.block_rendering.BlockRenderingSettings"}) {
            try {
                return Class.forName(name).getField("INSTANCE").get(null);
            } catch (ClassNotFoundException ignored) {
                // try the older package
            }
        }
        return null;
    }

    /** For /lumen status. */
    public static String describe() {
        if (!LumenConfig.get().placedLights) {
            return "off (setting)";
        }
        if (failure != null) {
            return "FAILED (" + failure + ")";
        }
        if (original == null) {
            return "waiting for a shader pack";
        }
        return lastAdded + " block states use a vanilla light's id"
                + (lastCoded > 0 ? " (" + lastCoded + " with your exact colour)" : "");
    }
}
