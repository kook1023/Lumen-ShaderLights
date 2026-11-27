package io.github.kook1023.lumen.iris;

import io.github.kook1023.lumen.config.Colors;
import org.joml.Vector3f;

/**
 * Colour-coded light ids for placed blocks, understood by packs that carry {@code shaders/lumen.properties}
 * (the PhotonFork). The block id is {@code 10000 + 1024 + code} with
 * <pre>
 *   code = hue (6 bits, 0-63) | saturation << 6 (3 bits, 0-7) | intensity << 9 (4 bits, 1-15)
 * </pre>
 * Brightness is not stored: the pack shows the colour at full brightness, scaled by the intensity. Must match the
 * pack's {@code include/lighting/lpv/lumen_color_ids.glsl}.
 */
public final class PlacedColorId {
    public static final int BASE = 10000 + 1024;

    private PlacedColorId() {
    }

    public static int encode(Vector3f color, int level) {
        int rgb = (Math.round(color.x * 255f) << 16) | (Math.round(color.y * 255f) << 8) | Math.round(color.z * 255f);
        float[] hsv = Colors.rgbToHsv(rgb);
        int hue = Math.round(hsv[0] * 64f) & 63;
        int saturation = Math.max(0, Math.min(7, Math.round(hsv[1] * 7f)));
        int lvl = Math.max(1, Math.min(15, level));
        return BASE + (hue | (saturation << 6) | (lvl << 9));
    }

    /** What the pack will show (for tests and /lumen status): 0xRRGGBB at full brightness. */
    public static int decodeRgb(int id) {
        int code = id - BASE;
        return Colors.hsvToRgb((code & 63) / 64f, ((code >> 6) & 7) / 7f, 1f);
    }

    public static int decodeLevel(int id) {
        return ((id - BASE) >> 9) & 15;
    }
}
