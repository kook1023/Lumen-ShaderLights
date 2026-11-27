package io.github.kook1023.lumen.iris;

import io.github.kook1023.lumen.LumenShaderLights;
import io.github.kook1023.lumen.config.FlickerGroups;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The light table: one row per distinct placed light the shader pack should treat specially (your exact colour,
 * intensity, flicker). A placed block then gets the block id {@code 11024 + row}, and packs that read the table
 * (the PhotonFork, via {@code customTexture.lumen_light_table = headys_lumen:light_table} in shaders.properties) look
 * up the rest. The table is a small texture the mod keeps up to date:
 * <pre>
 *   row r -> texels x = (r % 128) * 3 + 0..2, y = r / 128     (all alpha 255)
 *   texel 0: R,G = the pack's own block id (low, high byte)   B = flags: 1 custom colour, 2 flicker
 *   texel 1: R,G,B = custom colour
 *   texel 2: R = intensity 1-15, G = flicker speed 1-20, B = flicker strength 0-100 (percent)
 *   last texel (383, 127): the signature "LUM" (76, 85, 77, 255), so a pack can tell the table is really there
 * </pre>
 * The pack's own block id lets the pack keep treating the block as what it is (a torch, lava, a furnace) for
 * everything except the light it gives.
 */
public final class LightTable {
    public static final int BASE = 10000 + 1024;
    public static final int MAX_ROWS = 16383;            // ids stay below 32768; the last row's spot holds the signature
    public static final int ROWS_PER_LINE = 128;
    public static final int WIDTH = ROWS_PER_LINE * 3;   // 384
    public static final int HEIGHT = 128;
    /** "LUM" in the last texel: packs check it and treat our ids as plain torches if the table is missing. */
    public static final int SIGNATURE = (76 << 24) | (85 << 16) | (77 << 8) | 255;
    public static final ResourceLocation LOCATION = ResourceLocation.tryParse("headys_lumen:light_table");

    /** One row. rgb = -1: use the pack's own colour for that block. speed = 0: steady. */
    public record Row(int packId, int rgb, int level, int speed, int strength) {
        public boolean flickers() {
            return speed > 0 && strength > 0;
        }
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static final Map<Row, Integer> INDEX = new HashMap<>();
    private static DynamicTexture texture;
    private static boolean dirty;

    private LightTable() {
    }

    /** Registers the texture with Minecraft. Call on the render thread before shader packs load (client start). */
    public static synchronized void register() {
        if (texture != null) {
            return;
        }
        try {
            texture = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, WIDTH, HEIGHT, true));
            Minecraft.getInstance().getTextureManager().register(LOCATION, texture);
            dirty = true;
            upload();
        } catch (RuntimeException | LinkageError e) {
            LumenShaderLights.LOGGER.error("Could not create the light table texture", e);
        }
    }

    static synchronized void clear() {
        ROWS.clear();
        INDEX.clear();
        dirty = true;
    }

    /**
     * @param packId the pack's own block id for this block (what it would be without the mod)
     * @param color  your colour, or null to keep the pack's own
     * @param flicker the flicker, or null for steady
     * @return the block id to give the block, or -1 if the table is full
     */
    static synchronized int idFor(int packId, Vector3f color, int level, FlickerGroups.Spec flicker) {
        int rgb = color == null ? -1
                : (Math.round(color.x * 255f) << 16) | (Math.round(color.y * 255f) << 8) | Math.round(color.z * 255f);
        int speed = flicker == null ? 0 : Math.max(1, Math.min(20, Math.round(flicker.speed())));
        int strength = flicker == null ? 0 : Math.max(0, Math.min(100, Math.round(flicker.strength() * 100f)));
        Row row = new Row(packId, rgb, Math.max(0, Math.min(15, level)), speed, strength);
        Integer index = INDEX.get(row);
        if (index == null) {
            if (ROWS.size() >= MAX_ROWS) {
                return -1;
            }
            index = ROWS.size();
            ROWS.add(row);
            INDEX.put(row, index);
            dirty = true;
        }
        return BASE + index;
    }

    public static synchronized int size() {
        return ROWS.size();
    }

    /** The row behind a block id from {@link #idFor}, or null. */
    public static synchronized Row rowOf(int blockId) {
        int i = blockId - BASE;
        return i >= 0 && i < ROWS.size() ? ROWS.get(i) : null;
    }

    /** The three texels of a row as 0xRRGGBBAA (what the shader reads). */
    public static int[] texels(Row r) {
        int flags = (r.rgb() >= 0 ? 1 : 0) | (r.flickers() ? 2 : 0);
        int rgb = Math.max(0, r.rgb());
        return new int[] {
                rgba(r.packId() & 255, (r.packId() >> 8) & 255, flags),
                rgba((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255),
                rgba(r.level(), r.speed(), r.strength())};
    }

    private static int rgba(int r, int g, int b) {
        return (r << 24) | (g << 16) | (b << 8) | 255;
    }

    /** 0xRRGGBBAA -> the ABGR that NativeImage takes */
    static int abgr(int c) {
        return (c & 255) << 24 | ((c >> 8) & 255) << 16 | ((c >> 16) & 255) << 8 | ((c >>> 24) & 255);
    }

    /** Writes the rows into the texture and sends it to the graphics card. Call on the render thread. */
    public static synchronized void upload() {
        if (!dirty) {
            return;
        }
        if (texture == null) {
            register(); // calls upload() again once it exists
            return;
        }
        NativeImage image = texture.getPixels();
        if (image == null) {
            return;
        }
        for (int i = 0; i < ROWS.size(); i++) {
            int[] t = texels(ROWS.get(i));
            int x = (i % ROWS_PER_LINE) * 3;
            int y = i / ROWS_PER_LINE;
            for (int k = 0; k < 3; k++) {
                image.setPixelRGBA(x + k, y, abgr(t[k]));
            }
        }
        image.setPixelRGBA(WIDTH - 1, HEIGHT - 1, abgr(SIGNATURE));
        texture.upload();
        dirty = false;
    }
}
