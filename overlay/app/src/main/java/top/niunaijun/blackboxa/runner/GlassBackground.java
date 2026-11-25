package top.niunaijun.blackboxa.runner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The live backdrop of the app, kept calm on purpose: a smooth blue gradient (deep navy blue at the top,
 * ocean blue at the bottom) with a few big soft lights, all of them blue, that drift very slowly, breathe
 * and slowly change between two blues. There are no lines, rings, dots or other shapes and no other
 * colours. (Before build 47 it also had violet, teal and rose lights, wave lines, spreading rings, a grid
 * of dots and floating sparks, which was too busy.)
 *
 * <p>Where the lights are and what they are made of lives in {@link BackdropMotion}. The screen is drawn
 * with a handful of gradients that are only moved around, so a frame is cheap, and since everything moves
 * so slowly it is drawn about 20 times a second, which looks the same as more. A very faint grain on top
 * keeps the dark gradient smooth instead of showing steps of colour. It moves for as long as the screen
 * shows it, whatever the phone's animation and battery saver settings say.
 */
public class GlassBackground extends View {
    private static final long FRAME_MS = 50;
    /** Where on its path every light sits at the start (and when nothing moves). */
    private static final double STILL_SECONDS = 9.0;

    /** A light with the two gradients it is drawn with, one for each of its blues. */
    private static final class Glow {
        final BackdropMotion.Light light;
        RadialGradient shaderA, shaderB;

        Glow(BackdropMotion.Light light) {
            this.light = light;
        }
    }

    private final Glow[] glows = new Glow[BackdropMotion.LIGHTS.length];

    private final Paint basePaint = new Paint();
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint();
    private final Paint edgePaint = new Paint();

    private Bitmap grainTile;
    private final boolean fadeEdges;

    private float density;
    private float baseSize;
    private boolean running;
    private boolean attached;
    private double clock = STILL_SECONDS;
    private long lastFrame;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
        }
    };

    public GlassBackground(Context context) {
        this(context, false);
    }

    /**
     * @param fadeEdges blend the top and bottom edges into the flat colours the status bar and the
     *                  navigation bar use, for screens that do not draw behind the bars
     */
    public GlassBackground(Context context, boolean fadeEdges) {
        super(context);
        this.fadeEdges = fadeEdges;
        init();
    }

    public GlassBackground(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.fadeEdges = true;
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
        basePaint.setDither(true);
        blobPaint.setDither(true);
        for (int i = 0; i < glows.length; i++) {
            glows[i] = new Glow(BackdropMotion.LIGHTS[i]);
        }
        buildGrain();
    }

    // ------------------------------------------------------------------ size and shaders

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }
        basePaint.setShader(new LinearGradient(0, 0, 0, h, BackdropMotion.BASE_COLORS,
                BackdropMotion.BASE_STOPS, Shader.TileMode.CLAMP));

        baseSize = Math.max(w, h * 0.62f);
        for (Glow glow : glows) {
            float r = baseSize * glow.light.radius;
            glow.shaderA = softLight(glow.light.colorA, glow.light.peak, r);
            glow.shaderB = softLight(glow.light.colorB, glow.light.peak, r);
        }

        if (fadeEdges) {
            float top = 72f * density;
            float bottom = 56f * density;
            // One gradient for both edges: the flat colour of the bar, clear in between, the flat colour again.
            float a = Math.min(0.45f, top / h);
            float b = Math.max(0.55f, 1f - bottom / h);
            edgePaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Glass.BG_TOP, Glass.withAlpha(Glass.BG_TOP, 0f),
                            Glass.withAlpha(Glass.BG_BOTTOM, 0f), Glass.BG_BOTTOM},
                    new float[]{0f, a, b, 1f}, Shader.TileMode.CLAMP));
        }
    }

    /** A soft round light: strongest in the middle, fading smoothly to nothing at its edge. */
    private static RadialGradient softLight(int color, float peak, float radius) {
        int rgb = color & 0x00FFFFFF;
        int[] colors = new int[BackdropMotion.GLOW_STOPS.length];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = rgb | (Math.round(255f * peak * BackdropMotion.GLOW_STRENGTH[i]) << 24);
        }
        return new RadialGradient(0, 0, radius, colors, BackdropMotion.GLOW_STOPS, Shader.TileMode.CLAMP);
    }

    /**
     * A tile of very faint noise (at most 2 percent white or black): a dark gradient drawn on a screen
     * that has 256 steps per colour shows its steps as bands, and this breaks them up without being seen.
     */
    private void buildGrain() {
        final int size = 128;
        int[] pixels = new int[size * size];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < pixels.length; i++) {
            int alpha = random.nextInt(6);
            pixels[i] = (alpha << 24) | (random.nextBoolean() ? 0x00FFFFFF : 0x00000000);
        }
        grainTile = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        grainPaint.setShader(new BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0 || glows[0].shaderA == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (running) {
            if (lastFrame != 0) {
                // (a long gap, for example after the screen was off, must not make things jump)
                clock += Math.min(now - lastFrame, 100L) / 1000.0;
            }
            lastFrame = now;
        }
        final double t = clock;

        canvas.drawRect(0, 0, w, h, basePaint);

        // The soft lights, each one fading between its two blues.
        for (Glow glow : glows) {
            BackdropMotion.Light light = glow.light;
            float x = BackdropMotion.x(light, t) * w;
            float y = BackdropMotion.y(light, t) * h;
            float mix = BackdropMotion.mix(light, t);
            float breath = BackdropMotion.breath(light, t);
            float lift = BackdropMotion.lift(light, t);
            canvas.save();
            canvas.translate(x, y);
            canvas.scale(breath, breath);
            float r = baseSize * light.radius;
            blobPaint.setShader(glow.shaderA);
            blobPaint.setAlpha(Math.round(255f * (1f - mix) * lift));
            canvas.drawCircle(0, 0, r, blobPaint);
            blobPaint.setShader(glow.shaderB);
            blobPaint.setAlpha(Math.round(255f * mix * lift));
            canvas.drawCircle(0, 0, r, blobPaint);
            canvas.restore();
        }

        canvas.drawRect(0, 0, w, h, grainPaint);

        if (fadeEdges && edgePaint.getShader() != null) {
            canvas.drawRect(0, 0, w, h, edgePaint);
        }

        if (running) {
            postOnAnimationDelayed(tick, FRAME_MS);
        }
    }

    // ------------------------------------------------------------------ running and stopping

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateRunning();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateRunning();
    }

    private void updateRunning() {
        // It moves whenever it can be seen, whatever the phone's animation or battery settings say.
        boolean shouldRun = attached && getWindowVisibility() == VISIBLE && getVisibility() == VISIBLE;
        if (shouldRun && !running) {
            running = true;
            lastFrame = 0;
            invalidate();
        } else if (!shouldRun && running) {
            stop();
            invalidate();
        }
    }

    private void stop() {
        running = false;
        removeCallbacks(tick);
    }
}
