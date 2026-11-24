package io.github.kook1023.lumen.iris;

import org.joml.Vector3f;

/**
 * Rethinking Voxels does not look up a colour table for held items. It treats the item id itself as a colour:
 * <pre>
 *   red   = id % 16        (id &amp; 15)
 *   green = (id / 16) % 16 ((id &gt;&gt; 4) &amp; 15)
 *   blue  = (id / 256) % 16 ((id &gt;&gt; 8) &amp; 15)
 * </pre>
 * and multiplies each by 16 in the shader. Ids 1..9999 count as lights, so a 12-bit colour (max 4095) always fits.
 * That means we can send ANY colour (16 steps per channel) just by choosing the right id.
 */
public final class RgbId {
    private RgbId() {
    }

    /** @return the colour (0xRRGGBB) the shader would read from this item id. */
    public static int decode(int id) {
        int r = (id & 15) * 16;
        int g = ((id >> 4) & 15) * 16;
        int b = ((id >> 8) & 15) * 16;
        return (r << 16) | (g << 8) | b;
    }

    /**
     * @param color 0..1 per channel
     * @param scale multiplier applied to the colour first (used to dim it for a lower intensity)
     * @return an item id (always at least 1, because id 0 means "no light")
     */
    public static int encode(Vector3f color, float scale) {
        int r = nibble(color.x * scale);
        int g = nibble(color.y * scale);
        int b = nibble(color.z * scale);
        int id = r | (g << 4) | (b << 8);
        return id == 0 ? 1 : id;
    }

    private static int nibble(float v) {
        return Math.max(0, Math.min(15, Math.round(v * 15f)));
    }
}
