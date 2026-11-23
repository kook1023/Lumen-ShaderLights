package dev.shadertorches.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.shadertorches.ShaderTorches;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The settings stored in config/shadertorches.json. */
public final class TorchConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("shadertorches.json");
    private static volatile TorchConfig current = new TorchConfig();

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
    /** Your own items. */
    public List<Entry> items = new ArrayList<>();

    public static TorchConfig get() {
        return current;
    }

    /** Reads the file (creating it with defaults if missing) and activates it. */
    public static synchronized void load() {
        TorchConfig loaded = null;
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                loaded = GSON.fromJson(reader, TorchConfig.class);
            } catch (IOException | JsonParseException e) {
                // Do not overwrite a file the user may be halfway through editing.
                ShaderTorches.LOGGER.warn("Could not read {} - keeping the previous settings: {}",
                        FILE.getFileName(), e.toString());
                return;
            }
        }
        boolean writeDefaults = loaded == null;
        if (loaded == null) {
            loaded = new TorchConfig();
        }
        loaded.sanitize();
        current = loaded;
        TorchMatcher.rebuild(loaded);
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
            ShaderTorches.LOGGER.warn("Could not write {}: {}", FILE.getFileName(), e.toString());
        }
    }

    /** Call after changing fields: cleans values, writes the file and updates the live rules at once. */
    public static synchronized void applyAndSave() {
        current.sanitize();
        save();
        TorchMatcher.rebuild(current);
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

    private static String text(String s) {
        return s == null ? "" : s.trim();
    }

    private void sanitize() {
        defaultLightLevel = Math.max(1, Math.min(15, defaultLightLevel));
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
                clean.add(e);
            }
        }
        items = clean;
    }
}
