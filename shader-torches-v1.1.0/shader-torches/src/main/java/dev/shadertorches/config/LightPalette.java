package dev.shadertorches.config;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla light-emitting items that shader packs commonly know about (via item.properties), each with an
 * approximate light colour. Used for two things:
 *  - the "Pretend to be" dropdown in the config screen, and
 *  - picking the closest-coloured item the active shader pack actually knows (for packs that don't read
 *    Iris' heldBlockLightColor).
 * The colours are typical values; every pack defines its own, so treat them as a good guess.
 */
public final class LightPalette {
    public record Ref(String id, int rgb) {
        public ResourceLocation location() {
            return ResourceLocation.tryParse(id);
        }
    }

    public static final List<Ref> REFS = List.of(
            new Ref("minecraft:torch", 0xFF9429),
            new Ref("minecraft:lantern", 0xFFB359),
            new Ref("minecraft:campfire", 0xFF8C33),
            new Ref("minecraft:jack_o_lantern", 0xFF8C26),
            new Ref("minecraft:shroomlight", 0xFF9940),
            new Ref("minecraft:glowstone", 0xFFCC66),
            new Ref("minecraft:redstone_lamp", 0xFFA659),
            new Ref("minecraft:glow_berries", 0xFFBF4D),
            new Ref("minecraft:ochre_froglight", 0xFFD959),
            new Ref("minecraft:lava_bucket", 0xFF6614),
            new Ref("minecraft:magma_block", 0xFF590F),
            new Ref("minecraft:redstone_torch", 0xFF1F14),
            new Ref("minecraft:soul_torch", 0x40BFF2),
            new Ref("minecraft:soul_lantern", 0x40BFF2),
            new Ref("minecraft:soul_campfire", 0x40BFF2),
            new Ref("minecraft:sea_lantern", 0xA6E6FF),
            new Ref("minecraft:conduit", 0x73D9FF),
            new Ref("minecraft:beacon", 0xCCF2FF),
            new Ref("minecraft:end_rod", 0xF2EBFF),
            new Ref("minecraft:amethyst_cluster", 0xB373FF),
            new Ref("minecraft:crying_obsidian", 0x8C26FF),
            new Ref("minecraft:respawn_anchor", 0xA626E6),
            new Ref("minecraft:pearlescent_froglight", 0xF299E6),
            new Ref("minecraft:verdant_froglight", 0x8CFF80),
            new Ref("minecraft:sea_pickle", 0x73FF8C));

    private LightPalette() {
    }

    /** Ids for the dropdown. */
    public static List<String> ids() {
        List<String> ids = new ArrayList<>(REFS.size());
        for (Ref ref : REFS) {
            ids.add(ref.id());
        }
        return ids;
    }

    /**
     * How different two colours look, ignoring brightness (intensity is a separate setting).
     * Lower = closer.
     */
    public static float distance(int a, int b) {
        float[] x = normalised(a);
        float[] y = normalised(b);
        float dr = x[0] - y[0];
        float dg = x[1] - y[1];
        float db = x[2] - y[2];
        return 2f * dr * dr + 4f * dg * dg + 3f * db * db;
    }

    private static float[] normalised(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f;
        float g = ((rgb >> 8) & 255) / 255f;
        float b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b));
        if (max <= 0f) {
            return new float[] {1f, 1f, 1f};
        }
        return new float[] {r / max, g / max, b / max};
    }
}
