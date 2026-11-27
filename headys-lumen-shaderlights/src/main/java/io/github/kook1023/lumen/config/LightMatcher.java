package io.github.kook1023.lumen.config;

import io.github.kook1023.lumen.LumenShaderLights;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Decides which items count as torches and what light they give. Compiled from the config; cached per item. */
public final class LightMatcher {
    /**
     * What to do for a matched item.
     * @param level      light level 1-15 (0 = not a torch / excluded)
     * @param color      light colour (0..1 per channel) or null to leave the pack's own colour
     * @param pretendTo  item the shader should think this is, or null for automatic
     * @param exact      true for items from your list: the level is used as-is (it can dim an item that glows by
     *                   itself). false for auto-detected torches: the level only ever raises the light.
     * @param flickerSpeed    0 = steady light; otherwise roughly how many times per second the flame changes
     * @param flickerStrength how far the flicker dims, 0..1 of the intensity
     */
    public record Match(int level, Vector3f color, ResourceLocation pretendTo, boolean exact,
                        float flickerSpeed, float flickerStrength) {
        public Match(int level, Vector3f color, ResourceLocation pretendTo, boolean exact) {
            this(level, color, pretendTo, exact, 0f, 0f);
        }

        public boolean flickers() {
            return flickerSpeed > 0f && flickerStrength > 0f;
        }
    }

    public static final Match NONE = new Match(0, null, null, false);

    private static final Pattern ID_CHARS = Pattern.compile("[a-z0-9_.\\-/*:]+");
    private static volatile Snapshot snapshot = new Snapshot(false, false, 14, List.of());

    private LightMatcher() {
    }

    /** @param source the entry's id as written in your list (for /lumen removeheld) */
    private record Rule(Pattern pattern, Match match, String source) {
    }

    /**
     * Auto-detect families: a modded item whose id ends in {@code suffix} poses as {@code vanilla}, at that vanilla
     * block's own light level. Checked in this order, before the plain "ends in torch" rule, so soul and redstone
     * torches are not turned into normal torches.
     */
    private record Family(String suffix, ResourceLocation vanilla, Vector3f color, int level) {
        static Family as(String suffix, String vanilla, int level) {
            return new Family(suffix, ResourceLocation.tryParse(vanilla), null, level);
        }

        /** For lights with no vanilla counterpart in 1.20.1 (copper): a colour instead. */
        static Family colored(String suffix, int rgb, int level) {
            return new Family(suffix, null,
                    new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f), level);
        }

        Match match() {
            return new Match(level, color, vanilla, false);
        }
    }

    // Most specific first: "soul_lantern" before "lantern", "redstone_torch" before "torch".
    private static final List<Family> FAMILIES = List.of(
            Family.as("soul_torch", "minecraft:soul_torch", 10),
            Family.as("soul_lantern", "minecraft:soul_lantern", 10),
            Family.as("redstone_torch", "minecraft:redstone_torch", 7),
            Family.as("red_stone_torch", "minecraft:redstone_torch", 7),
            Family.colored("copper_torch", 0x6BFF8E, 14),       // copper fire is green
            Family.colored("copper_lantern", 0x6BFF8E, 15),
            Family.as("sea_lantern", "minecraft:sea_lantern", 15), // so modded sea lanterns stay blue, not lantern orange
            Family.as("jack_o_lantern", "minecraft:jack_o_lantern", 15),
            Family.as("lantern", "minecraft:lantern", 15));

    private static final class Snapshot {
        final boolean enabled;
        final boolean matchSuffix;
        final Match suffixMatch;
        final List<Rule> rules;
        final Map<Item, Match> cache = new ConcurrentHashMap<>();

        Snapshot(boolean enabled, boolean matchSuffix, int suffixLevel, List<Rule> rules) {
            this.enabled = enabled;
            this.matchSuffix = matchSuffix;
            this.suffixMatch = new Match(suffixLevel, null, null, false);
            this.rules = rules;
        }

        Match compute(Item item) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            String full = id.toString();
            for (Rule rule : rules) {
                if (rule.pattern().matcher(full).matches()) {
                    return rule.match();
                }
            }
            // Vanilla items are skipped: Iris already lights those (soul torch, lantern, ...).
            if (!matchSuffix || "minecraft".equals(id.getNamespace())) {
                return NONE;
            }
            String path = id.getPath();
            for (Family f : FAMILIES) {
                if (path.endsWith(f.suffix())) {
                    return f.match();
                }
            }
            if (path.endsWith("torch")) {
                return suffixMatch;
            }
            return NONE;
        }
    }

    /** Recompiles the rules. Called on load, on save and on world join. */
    public static void rebuild(LumenConfig cfg) {
        List<Rule> rules = new ArrayList<>();
        for (LumenConfig.Entry entry : cfg.items) {
            Rule rule = parse(entry);
            if (rule != null) {
                rules.add(rule);
            } else {
                LumenShaderLights.LOGGER.warn("Ignoring invalid item entry '{}'", entry.id);
            }
        }
        snapshot = new Snapshot(cfg.enabled, cfg.matchTorchSuffix, cfg.defaultLightLevel, List.copyOf(rules));
    }

    /** @return what applies to this stack, or {@link #NONE} (level 0) if it is not a torch. */
    public static Match matchFor(ItemStack stack) {
        Snapshot s = snapshot;
        if (!s.enabled || stack.isEmpty()) {
            return NONE;
        }
        return s.cache.computeIfAbsent(stack.getItem(), s::compute);
    }

    /** @return the id of the list entry (possibly a wildcard) that matches this stack, or null if none does. */
    public static String listEntryFor(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String full = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        for (Rule rule : snapshot.rules) {
            if (rule.pattern().matcher(full).matches()) {
                return rule.source();
            }
        }
        return null;
    }

    /** @return a translation key describing the problem, or empty if the id is fine (or blank). */
    public static Optional<String> validateId(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return glob(raw) == null ? Optional.of("headys_lumen.config.invalid_id") : Optional.empty();
    }

    private static Rule parse(LumenConfig.Entry e) {
        Pattern pattern = glob(e.id);
        if (pattern == null) {
            return null;
        }
        int rgb = Colors.parseHex(e.color);
        Vector3f color = rgb < 0
                ? null
                : new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f);
        ResourceLocation pretend = e.pretendToBe == null || e.pretendToBe.isBlank()
                ? null
                : ResourceLocation.tryParse(e.pretendToBe.trim().toLowerCase(Locale.ROOT));
        int level = Math.max(0, Math.min(15, e.level));
        if (level == 0) {
            // Level 0 = "no held light at all" (unlit / burnt-out torches): no colour, no pretend.
            return new Rule(pattern, new Match(0, null, null, true), e.id);
        }
        float speed = e.flicker ? Math.max(1, Math.min(20, e.flickerSpeed)) : 0f;
        float strength = e.flicker ? Math.max(5, Math.min(100, e.flickerStrength)) / 100f : 0f;
        return new Rule(pattern, new Match(level, color, pretend, true, speed, strength), e.id);
    }

    private static Pattern glob(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || !ID_CHARS.matcher(s).matches()) {
            return null;
        }
        if (s.indexOf(':') < 0) {
            s = "*:" + s; // bare "glow_stick" matches that item in any namespace
        }
        StringBuilder regex = new StringBuilder();
        for (char c : s.toCharArray()) {
            regex.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.toString());
    }
}
