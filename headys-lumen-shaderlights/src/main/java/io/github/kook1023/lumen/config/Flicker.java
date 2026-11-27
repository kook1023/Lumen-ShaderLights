package io.github.kook1023.lumen.config;

import java.util.function.DoubleSupplier;

/**
 * Flame-like flicker for held lights: smooth random noise (two layers, like a candle that wavers and sometimes
 * dips), turned into a brightness factor between {@code 1 - strength} and 1 that stays near 1 most of the time.
 */
public final class Flicker {
    /** Seconds; replaceable in tests. */
    static DoubleSupplier clock = () -> System.nanoTime() / 1.0e9;

    private Flicker() {
    }

    /** Brightness factor for this match right now (1 = no flicker). {@code seed} keeps both hands out of step. */
    public static float factor(LightMatcher.Match m, int seed) {
        if (m == null || !m.flickers()) {
            return 1f;
        }
        return factorAt(clock.getAsDouble(), m.flickerSpeed(), m.flickerStrength(), seed);
    }

    /** @return between {@code 1 - strength} and 1 */
    public static float factorAt(double seconds, float speed, float strength, int seed) {
        double t = seconds * speed;
        double n = 0.65 * noise(t, seed) + 0.35 * noise(t * 2.7 + 17.3, seed + 101);
        // Squared, so the flame stays near full brightness most of the time and dips now and then
        float dip = (float) Math.max(0.0, Math.min(1.0, n * n));
        return 1f - Math.max(0f, Math.min(1f, strength)) * dip;
    }

    /** Smooth 1-D value noise in 0..1. */
    static double noise(double t, int seed) {
        long i = (long) Math.floor(t);
        double f = t - i;
        double u = f * f * (3.0 - 2.0 * f);
        double a = hash(i, seed);
        double b = hash(i + 1, seed);
        return a + (b - a) * u;
    }

    private static double hash(long i, int seed) {
        long h = i * 0x9E3779B97F4A7C15L + seed * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) / (double) (1L << 53);
    }
}
