package top.niunaijun.blackboxa.runner;

/**
 * The numbers behind the calm blue backdrop ({@link GlassBackground}): where the big soft lights are at
 * any moment, how bright they are, and what they are made of. It is plain maths with no Android classes
 * in it, so it can be checked on a computer: that every colour is a blue, that nothing moves fast,
 * and that the brightest the backdrop ever gets still leaves white text easy to read.
 *
 * <p>Everything here is slow on purpose. The fastest a light moves is about 1.6 percent of the screen
 * width per second, and a change of brightness takes tens of seconds.
 */
final class BackdropMotion {
    private BackdropMotion() {
    }

    static final double TWO_PI = Math.PI * 2.0;

    /** The gradient behind the lights, top to bottom (the same colours the status and navigation bars use). */
    static final int[] BASE_COLORS = {Glass.BG_TOP, Glass.BG_MID, Glass.BG_BOTTOM};
    static final float[] BASE_STOPS = {0f, 0.52f, 1f};

    /** Across a light, from its centre to its edge: where the stops are, and how strong the light is there. */
    static final float[] GLOW_STOPS = {0f, 0.35f, 0.70f, 1f};
    static final float[] GLOW_STRENGTH = {1f, 0.55f, 0.16f, 0f};

    /** One big soft light: it drifts along a slow loop, breathes, and fades between two blues. */
    static final class Light {
        final float cx, cy;          // centre of its loop, as a fraction of the screen
        final float ax, ay;          // size of the loop, as a fraction of the screen
        final float px, py;          // seconds for one trip along each axis
        final float ox, oy;          // where on the loop it starts
        final float radius;          // as a fraction of the base size
        final int colorA, colorB;    // the two blues it fades between
        final float peak;            // how strong it is at its centre, 0..1
        final float fade;            // seconds for one change of blue
        final float fadeOffset;

        Light(float cx, float cy, float ax, float ay, float px, float py, float ox, float oy,
              float radius, int colorA, int colorB, float peak, float fade, float fadeOffset) {
            this.cx = cx;
            this.cy = cy;
            this.ax = ax;
            this.ay = ay;
            this.px = px;
            this.py = py;
            this.ox = ox;
            this.oy = oy;
            this.radius = radius;
            this.colorA = colorA;
            this.colorB = colorB;
            this.peak = peak;
            this.fade = fade;
            this.fadeOffset = fadeOffset;
        }
    }

    /** Only blues: from a deep royal blue to a pale sky blue. Nothing violet, teal or pink. */
    static final Light[] LIGHTS = {
            // Top right: the brightest, and the place the eye rests on.
            new Light(0.80f, 0.16f, 0.12f, 0.05f, 48f, 62f, 0.0f, 1.3f, 0.66f,
                    0xFF3C8DFF, 0xFF4DB3FF, 0.23f, 44f, 0.0f),
            // A wide deep-blue wash that gives the whole screen its colour.
            new Light(0.38f, 0.42f, 0.16f, 0.07f, 66f, 54f, 2.1f, 0.4f, 0.95f,
                    0xFF1E56D8, 0xFF2A6CF0, 0.19f, 56f, 1.7f),
            // Left, towards the middle.
            new Light(0.10f, 0.60f, 0.12f, 0.07f, 57f, 46f, 4.0f, 2.4f, 0.62f,
                    0xFF2F7BFF, 0xFF5AA5FF, 0.17f, 50f, 3.1f),
            // Lower right: the coolest blue.
            new Light(0.86f, 0.82f, 0.10f, 0.06f, 44f, 68f, 1.2f, 3.3f, 0.70f,
                    0xFF2B9BFF, 0xFF38B4FF, 0.17f, 54f, 4.4f),
            // A faint glow along the bottom edge.
            new Light(0.45f, 0.95f, 0.18f, 0.03f, 70f, 50f, 5.2f, 0.9f, 0.55f,
                    0xFF4F8DFF, 0xFF6DB7FF, 0.105f, 60f, 5.6f),
    };

    /** Where the light is on the horizontal axis, as a fraction of the width. */
    static float x(Light light, double seconds) {
        return (float) (light.cx + light.ax * Math.sin(TWO_PI * seconds / light.px + light.ox));
    }

    /** Where the light is on the vertical axis, as a fraction of the height. */
    static float y(Light light, double seconds) {
        return (float) (light.cy + light.ay * Math.sin(TWO_PI * seconds / light.py + light.oy));
    }

    /** 0 is the first blue of the light, 1 the second. */
    static float mix(Light light, double seconds) {
        return (float) (0.5 + 0.5 * Math.sin(TWO_PI * seconds / light.fade + light.fadeOffset));
    }

    /** The light swells and shrinks a little, once in 40 to 60 seconds: between 0.95 and 1.05 times its size. */
    static float breath(Light light, double seconds) {
        return (float) (1.0 + 0.05 * Math.sin(TWO_PI * seconds / (light.py * 0.9) + light.ox));
    }

    /** How much of its strength the light shows right now: between 0.76 and 1. */
    static float lift(Light light, double seconds) {
        return (float) (0.88 + 0.12 * Math.sin(TWO_PI * seconds / (light.px * 0.8) + light.oy));
    }

    /**
     * How strong a light is at a distance from its centre ({@code distance} is 0 at the centre and 1 at the
     * edge of its circle): the same curve as the gradient the screen draws, which fades from the stops of
     * {@link #GLOW_STOPS} to nothing.
     */
    static float glow(float peak, float distance) {
        if (distance >= 1f) {
            return 0f;
        }
        for (int i = 1; i < GLOW_STOPS.length; i++) {
            if (distance <= GLOW_STOPS[i]) {
                float span = GLOW_STOPS[i] - GLOW_STOPS[i - 1];
                float f = span <= 0f ? 1f : (distance - GLOW_STOPS[i - 1]) / span;
                float s = GLOW_STRENGTH[i - 1] + (GLOW_STRENGTH[i] - GLOW_STRENGTH[i - 1]) * f;
                return peak * s;
            }
        }
        return 0f;
    }
}
