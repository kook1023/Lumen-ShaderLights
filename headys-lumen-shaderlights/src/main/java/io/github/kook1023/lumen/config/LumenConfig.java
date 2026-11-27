package io.github.kook1023.lumen.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.kook1023.lumen.LumenShaderLights;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The settings stored in config/headys_lumen.json. */
public final class LumenConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("headys_lumen.json");
    private static volatile LumenConfig current = new LumenConfig();

    /** One configured item (or wildcard) with its light settings. */
    public static final class Entry {
        /** "modid:item", a wildcard like "modid:*_lamp", or a bare "item" matching any namespace. */
        public String id = "";
        /** Intensity: held light level 0-15 (0 = leave this item alone). Raises light, never lowers it. */
        public int level = 14;
        /** Light colour as "#RRGGBB", or "" to leave the colour to the shader pack. */
        public String color = "";
        /** Item the shader should think this is (e.g. "minecraft:sea_lantern"), or "" for automatic. */
        public String pretendToBe = "";
        /** Held light flickers like a flame. */
        public boolean flicker = false;
        /** Flicker speed 1-20 (roughly changes per second). */
        public int flickerSpeed = 6;
        /** How much the flicker dims, in percent of the intensity (5-100). */
        public int flickerStrength = 35;

        public Entry() {
        }
    }

    /** Master switch. */
    public boolean enabled = true;
    /** Auto-detect non-vanilla items whose id path ends in "torch" (covers "_torch" too). */
    public boolean matchTorchSuffix = true;
    /** Light level (1-15) for auto-detected items. */
    public int defaultLightLevel = 14;
    /** Report matched items to the shader as minecraft:torch (if nothing better applies). */
    public boolean spoofTorchItemId = true;
    /** Send the entry's colour to the shader (Iris' heldBlockLightColor uniform). */
    public boolean sendLightColor = true;
    /** For packs without colour support: pose as the pack-known item whose colour is closest. */
    public boolean pickNearestColorItem = true;
    /**
     * Also change how your items are DRAWN (Iris' currentRenderedItemId), for packs that light the hand from the
     * rendered item itself, such as current Rethinking Voxels releases.
     */
    public boolean matchRenderedItem = true;
    /** Placed blocks: give placed modded lights the id of the vanilla light they pose as. */
    public boolean placedLights = true;
    /** Placed blocks: also change placed VANILLA blocks that your list recolours (e.g. a pink minecraft:torch). */
    public boolean placedRecolorVanilla = true;
    /** How to treat packs that read the item id as a colour (Rethinking Voxels). */
    public IdColorMode rgbIdMode = IdColorMode.AUTO;
    /** Your own items. */
    public List<Entry> items = new ArrayList<>();

    /**
     * Rethinking Voxels reads the held item id as a 12-bit colour (4 bits each for red, green, blue).
     * AUTO detects that by looking at the ids the pack gives to well-known items (torch = orange, ...).
     */
    public enum IdColorMode { AUTO, ON, OFF }

    public static LumenConfig get() {
        return current;
    }

    /** Reads the file (creating it with defaults if missing) and activates it. */
    public static synchronized void load() {
        LumenConfig loaded = null;
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                loaded = GSON.fromJson(reader, LumenConfig.class);
            } catch (IOException | JsonParseException e) {
                // Do not overwrite a file the user may be halfway through editing.
                LumenShaderLights.LOGGER.warn("Could not read {} - keeping the previous settings: {}",
                        FILE.getFileName(), e.toString());
                return;
            }
        }
        boolean writeDefaults = loaded == null;
        if (loaded == null) {
            loaded = new LumenConfig();
        }
        loaded.sanitize();
        current = loaded;
        LightMatcher.rebuild(loaded);
        notifyChanged();
        if (writeDefaults) {
            save();
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(current, writer);
            }
        } catch (IOException e) {
            LumenShaderLights.LOGGER.warn("Could not write {}: {}", FILE.getFileName(), e.toString());
        }
    }

    /** Call after changing fields: cleans values, writes the file and updates the live rules at once. */
    public static synchronized void applyAndSave() {
        current.sanitize();
        save();
        LightMatcher.rebuild(current);
        notifyChanged();
    }

    private static final java.util.List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Runs after every load or save (used to update placed lights). */
    public static void onChange(Runnable listener) {
        LISTENERS.add(listener);
    }

    private static void notifyChanged() {
        for (Runnable r : LISTENERS) {
            r.run();
        }
    }

    /** @return false if that item id was already listed. */
    public static synchronized boolean addItem(String id) {
        String clean = id.trim().toLowerCase(Locale.ROOT);
        for (Entry existing : current.items) {
            if (existing.id.equals(clean)) {
                return false;
            }
        }
        Entry entry = new Entry();
        entry.id = clean;
        entry.level = current.defaultLightLevel;
        current.items.add(entry);
        applyAndSave();
        return true;
    }

    /** @return false if no entry has exactly that id (wildcard entries are never removed this way). */
    public static synchronized boolean removeItem(String id) {
        String clean = id.trim().toLowerCase(Locale.ROOT);
        boolean removed = current.items.removeIf(e -> e.id.equals(clean));
        if (removed) {
            applyAndSave();
        }
        return removed;
    }

    private static String text(String s) {
        return s == null ? "" : s.trim();
    }

    private void sanitize() {
        defaultLightLevel = Math.max(1, Math.min(15, defaultLightLevel));
        if (rgbIdMode == null) {
            rgbIdMode = IdColorMode.AUTO;
        }
        List<Entry> clean = new ArrayList<>();
        if (items != null) {
            for (Entry e : items) {
                if (e == null || text(e.id).isEmpty()) {
                    continue;
                }
                e.id = text(e.id).toLowerCase(Locale.ROOT);
                e.level = Math.max(0, Math.min(15, e.level));
                int rgb = Colors.parseHex(e.color);
                e.color = rgb < 0 ? "" : Colors.toHex(rgb);
                e.pretendToBe = text(e.pretendToBe).toLowerCase(Locale.ROOT);
                e.flickerSpeed = Math.max(1, Math.min(20, e.flickerSpeed));
                e.flickerStrength = Math.max(5, Math.min(100, e.flickerStrength));
                clean.add(e);
            }
        }
        items = clean;
    }
}
