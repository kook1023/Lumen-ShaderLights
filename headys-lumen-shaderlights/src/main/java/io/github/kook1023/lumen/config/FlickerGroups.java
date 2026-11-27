package io.github.kook1023.lumen.config;

import io.github.kook1023.lumen.config.LumenConfig.FlickerMode;
import io.github.kook1023.lumen.config.LumenConfig.FlickerPreset;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;

/**
 * Which lights flicker. A light (held item or placed block, by its id) belongs to at most one group; vanilla ids
 * are listed, modded ids are sorted by their name ("..._torch", "...lantern", ...).
 */
public final class FlickerGroups {
    public enum Group { TORCHES, LANTERNS, FIRE, REDSTONE, SEA, NETHER, FROGLIGHTS, CAVE }

    /** Speed (changes per second) and strength (0..1 of the intensity) of a flickering light. */
    public record Spec(float speed, float strength) {
    }

    private static final Map<String, Group> VANILLA = Map.ofEntries(
            Map.entry("torch", Group.TORCHES), Map.entry("wall_torch", Group.TORCHES),
            Map.entry("soul_torch", Group.TORCHES), Map.entry("soul_wall_torch", Group.TORCHES),
            Map.entry("lantern", Group.LANTERNS), Map.entry("soul_lantern", Group.LANTERNS),
            Map.entry("campfire", Group.FIRE), Map.entry("soul_campfire", Group.FIRE),
            Map.entry("fire", Group.FIRE), Map.entry("soul_fire", Group.FIRE),
            Map.entry("jack_o_lantern", Group.FIRE), Map.entry("furnace", Group.FIRE),
            Map.entry("smoker", Group.FIRE), Map.entry("blast_furnace", Group.FIRE),
            Map.entry("redstone_torch", Group.REDSTONE), Map.entry("redstone_wall_torch", Group.REDSTONE),
            Map.entry("redstone_lamp", Group.REDSTONE), Map.entry("redstone_ore", Group.REDSTONE),
            Map.entry("deepslate_redstone_ore", Group.REDSTONE),
            Map.entry("sea_lantern", Group.SEA), Map.entry("sea_pickle", Group.SEA), Map.entry("conduit", Group.SEA),
            Map.entry("glowstone", Group.NETHER), Map.entry("shroomlight", Group.NETHER),
            Map.entry("magma_block", Group.NETHER), Map.entry("crying_obsidian", Group.NETHER),
            Map.entry("respawn_anchor", Group.NETHER), Map.entry("lava", Group.NETHER),
            Map.entry("lava_bucket", Group.NETHER), Map.entry("nether_portal", Group.NETHER),
            Map.entry("ochre_froglight", Group.FROGLIGHTS), Map.entry("verdant_froglight", Group.FROGLIGHTS),
            Map.entry("pearlescent_froglight", Group.FROGLIGHTS),
            Map.entry("glow_lichen", Group.CAVE), Map.entry("glow_berries", Group.CAVE),
            Map.entry("cave_vines", Group.CAVE), Map.entry("cave_vines_plant", Group.CAVE),
            Map.entry("amethyst_cluster", Group.CAVE), Map.entry("large_amethyst_bud", Group.CAVE),
            Map.entry("medium_amethyst_bud", Group.CAVE), Map.entry("small_amethyst_bud", Group.CAVE),
            Map.entry("sculk_sensor", Group.CAVE), Map.entry("calibrated_sculk_sensor", Group.CAVE),
            Map.entry("sculk_catalyst", Group.CAVE));

    /** Immersion mode: torches, lanterns, all fire lights, these, and cave lights except amethyst and sculk. */
    private static final Set<String> IMMERSION_EXTRA = Set.of(
            "shroomlight", "magma_block", "lava", "lava_bucket", "nether_portal", "crying_obsidian",
            "end_portal", "end_gateway", "enchanting_table", "ender_chest", "brewing_stand");

    private FlickerGroups() {
    }

    /** @return the group of this item or block id, or null */
    public static Group groupOf(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        String path = id.getPath();
        if ("minecraft".equals(id.getNamespace())) {
            if (path.endsWith("candle") || path.endsWith("candle_cake")) {
                return Group.FIRE;
            }
            return VANILLA.get(path);
        }
        if (path.contains("redstone") || path.contains("red_stone")) {
            return Group.REDSTONE;
        }
        if (path.contains("sea_lantern") || path.contains("sea_pickle")) {
            return Group.SEA;
        }
        if (path.contains("jack_o_lantern") || path.contains("campfire") || path.contains("candle")) {
            return Group.FIRE;
        }
        if (path.endsWith("torch")) {
            return Group.TORCHES;
        }
        if (path.contains("lantern")) {
            return Group.LANTERNS;
        }
        if (path.contains("froglight")) {
            return Group.FROGLIGHTS;
        }
        if (path.contains("glowstone") || path.contains("shroomlight") || path.contains("magma")
                || path.contains("crying_obsidian")) {
            return Group.NETHER;
        }
        if (path.contains("glow_lichen") || path.contains("glow_berr") || path.contains("amethyst")
                || path.contains("sculk")) {
            return Group.CAVE;
        }
        return null;
    }

    /** Immersion mode's pick. */
    public static boolean inImmersion(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        Group g = groupOf(id);
        String path = id.getPath();
        if (g == Group.TORCHES || g == Group.LANTERNS || g == Group.FIRE) {
            return true;
        }
        if (g == Group.CAVE) {
            return !path.contains("amethyst") && !path.contains("sculk");
        }
        if ("minecraft".equals(id.getNamespace())) {
            return IMMERSION_EXTRA.contains(path);
        }
        return path.contains("shroomlight") || path.contains("magma") || path.contains("crying_obsidian");
    }

    public static boolean groupOn(LumenConfig cfg, Group g) {
        if (g == null) {
            return false;
        }
        return switch (g) {
            case TORCHES -> cfg.flickerTorches;
            case LANTERNS -> cfg.flickerLanterns;
            case FIRE -> cfg.flickerFire;
            case REDSTONE -> cfg.flickerRedstone;
            case SEA -> cfg.flickerSea;
            case NETHER -> cfg.flickerNether;
            case FROGLIGHTS -> cfg.flickerFroglights;
            case CAVE -> cfg.flickerCave;
        };
    }

    /**
     * How (and whether) a light flickers.
     *
     * @param id     the held item's or placed block's id
     * @param entry  its row in your Light items list, or null
     * @param alsoAs a second id to sort it by if its own id fits no group (its "Pretend to be"), or null
     * @return the flicker, or null for a steady light
     */
    public static Spec resolve(LumenConfig cfg, ResourceLocation id, LumenConfig.Entry entry, ResourceLocation alsoAs) {
        FlickerMode mode = entry == null ? FlickerMode.FOLLOW : entry.flickerMode();
        if (mode == FlickerMode.ON) {
            return new Spec(clamp(entry.flickerSpeed, 1, 20), clamp(entry.flickerStrength, 5, 100) / 100f);
        }
        Spec defaults = new Spec(clamp(cfg.defaultFlickerSpeed, 1, 20), clamp(cfg.defaultFlickerStrength, 5, 100) / 100f);
        if (cfg.flickerPreset == FlickerPreset.ALL) {
            return defaults; // "Flicker all lights" also overrides rows set to Off
        }
        if (mode == FlickerMode.OFF) {
            return null;
        }
        if (cfg.flickerPreset == FlickerPreset.IMMERSION) {
            return inImmersion(id) || (groupOf(id) == null && inImmersion(alsoAs)) ? defaults : null;
        }
        Group g = groupOf(id);
        if (g == null) {
            g = groupOf(alsoAs);
        }
        return groupOn(cfg, g) ? defaults : null;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
