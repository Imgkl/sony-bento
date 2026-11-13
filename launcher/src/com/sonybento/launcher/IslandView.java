package com.sonybento.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

/**
 * Volume indicator that grows out of the left bezel as a black island, in place of the stock bar that
 * covers subtitles. Sizes are dp. State comes from the TV's volume-controller callback.
 *
 * <p>The outline moves only to open, close or pinch into the mute nub. Volume changes move the dial and
 * the counter inside it, so fast presses never shake the island.
 */
final class IslandView extends View {

    // The largest contour is 40 x 178 dp including its bezel fillets and rim.
    static final int WINDOW_WIDTH_DP = 48, WINDOW_HEIGHT_DP = 190;

    private static final float W = 40, H = 150, NW = 22, NH = 32, RADIUS = 18, FILLET = 14;
    private static final float NUMBER = 15, NUMBER_Y = 18, DIAL_TOP = 36, DIAL_BOTTOM = 12, TICK_GAP = 4;
    private static final long SHOW_MS = 1500, OPEN_MS = 120, CLOSE_MS = 180, PINCH_MS = 220;
    private static final char[] DIGITS = "0123456789".toCharArray();

    enum State { HIDDEN, FULL, NUB }

    /** Timed 0..1 progress. Retargeting starts from the current value, scaled to the distance left. */
    static final class Tween {
        float v, from, to;
        long start, duration = 1;
        boolean easeInOut;

        void go(float target, long ms, boolean inOut, long now) {
            if (target == to) return;
            from = v;
            to = target;
            start = now;
            duration = Math.max(1, Math.round(ms * Math.abs(target - v)));
            easeInOut = inOut;
        }

        void set(float value) {
            v = from = to = value;
        }

        /** Returns true when finished. */
        boolean tick(long now) {
            float t = clamp((now - start) / (float) duration);
            float u = 1 - t;
            float e = !easeInOut ? 1 - u * u * u : t < 0.5f ? 4 * t * t * t : 1 - 4 * u * u * u;
            v = from + (to - from) * e;
            return t >= 1;
        }
    }

    /** Critically damped spring in volume steps, stepped with semi-implicit Euler. */
    static final class Spring {
        float x, v, target;
        final float k, c;

        Spring(float k) {
            this.k = k;
            this.c = 2 * (float) Math.sqrt(k);
        }

        void step(float dt) {
            float a = k * (target - x) - c * v;
            v += a * dt;
            x += v * dt;
        }

        /** Snaps to the target once the motion is too small to see; returns true when at rest. */
        boolean settle() {
            if (Math.abs(target - x) > 0.01f || Math.abs(v) > 0.05f) return false;
            x = target;
            v = 0;
            return true;
        }
    }

    private final float dp;
    private int level, min, max = 100;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Drawable offAccent;
    private final float digitWidth, numberShift;
    /** Open drives hidden to full; pinch drives full (or hidden) to the mute nub. */
    private final Tween open = new Tween(), pinch = new Tween();
    /** Equal stiffness so the counter and the dial land on a new level at the same moment. */
    private final Spring dial = new Spring(700), counter = new Spring(700);
    private final Spring[] springs = {dial, counter};
    private final Runnable dismiss = this::dismiss;
    private final Runnable frameCallback = this::frame;

    private State state = State.HIDDEN;
    private boolean muted;
    private boolean animating;
    private long lastFrame;
    private VisibilityListener visibilityListener;

    interface VisibilityListener {
        void onVisibilityChanged(boolean visible);
    }

    IslandView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        text.setTypeface(HomeView.font(context, "IBMPlexMono-SemiBold.ttf"));
        text.setTextSize(d(NUMBER));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(HomeView.INK);
        Paint.FontMetrics m = text.getFontMetrics();
        numberShift = -(m.ascent + m.descent) / 2;
        digitWidth = text.measureText("0");
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(0xFF000000);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(d(1));
        rim.setColor(0x29FFFFFF);
        offAccent = context.getDrawable(R.drawable.ic_volume_3).mutate();
        offAccent.setTint(HomeView.ACCENT);
    }

    void setVisibilityListener(VisibilityListener listener) {
        visibilityListener = listener;
    }

    boolean isFullyVisible() {
        return state == State.FULL;
    }

    /** Even an unchanged level can request the HUD, such as another press at maximum volume. */
    void updateVolume(int next, int minimum, int maximum, boolean nextMuted, boolean showUi) {
        level = next;
        min = minimum;
        max = maximum;
        muted = nextMuted;
        dial.target = muted ? min : level;
        counter.target = level;
        if (showUi) show();
        else if (state == State.HIDDEN && muted) toNub();
        else if (state == State.NUB && !muted) dismiss();
        loop();
    }

    private void show() {
        long now = SystemClock.uptimeMillis();
        state = State.FULL;
        notifyVisibility();
        open.go(1, OPEN_MS, false, now);
        pinch.go(0, PINCH_MS, false, now);
        removeCallbacks(dismiss);
        postDelayed(dismiss, SHOW_MS);
    }

    private void dismiss() {
        if (muted) {
            toNub();
            return;
        }
        long now = SystemClock.uptimeMillis();
        state = State.HIDDEN;
        notifyVisibility();
        open.go(0, CLOSE_MS, true, now);
        pinch.go(0, CLOSE_MS, true, now);
        loop();
    }

    private void toNub() {
        state = State.NUB;
        notifyVisibility();
        pinch.go(1, PINCH_MS, false, SystemClock.uptimeMillis());
        loop();
    }

    /** Used for lost ownership and service teardown; never leave a stale mute nub behind. */
    void hideImmediately() {
        removeCallbacks(dismiss);
        removeCallbacks(frameCallback);
        state = State.HIDDEN;
        animating = false;
        open.set(0);
        pinch.set(0);
        dial.x = dial.target;
        counter.x = counter.target;
        dial.v = counter.v = 0;
        notifyVisibility();
        invalidate();
    }

    private void notifyVisibility() {
        if (visibilityListener != null) visibilityListener.onVisibilityChanged(isFullyVisible());
    }

    @Override protected void onDetachedFromWindow() {
        hideImmediately();
        super.onDetachedFromWindow();
    }

    private void loop() {
        if (animating) return;
        animating = true;
        lastFrame = SystemClock.uptimeMillis();
        postOnAnimation(frameCallback);
    }

    private void frame() {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(now - lastFrame, 32) / 2000f; // two substeps per frame
        lastFrame = now;
        boolean moving = !open.tick(now) | !pinch.tick(now);
        for (Spring s : springs) {
            s.step(dt);
            s.step(dt);
            moving |= !s.settle();
        }
        invalidate();
        if (moving) postOnAnimation(frameCallback);
        else animating = false;
    }

    private float d(float value) {
        return value * dp;
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static float smooth(float t) {
        t = clamp(t);
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float p = open.v, n = pinch.v;
        // Width and height grow together; the pinch pulls height in first and width after it.
        float w = lerp(W * p, NW, smooth((n - 0.25f) / 0.75f));
        float h = lerp(lerp(H * 0.3f, H, p), NH, smooth(n / 0.7f));
        if (w < 0.3f) return;
        float ww = d(w), cy = getHeight() / 2f;
        shape(ww, d(h), cy, 0.1f * (1 - p) * (1 - n));
        canvas.drawPath(shape, fill);
        // Black on a black picture has no edge; a faint rim keeps the island readable there.
        canvas.drawPath(shape, rim);
        canvas.save();
        canvas.clipPath(shape);
        float alpha = smooth(p) * (1 - smooth(n / 0.35f));
        if (alpha > 0) drawContent(canvas, (ww - d(W)) / 2 - (1 - alpha) * d(6), cy - d(H) / 2, alpha);
        float nubAlpha = smooth((n - 0.6f) / 0.4f);
        if (nubAlpha > 0) icon(canvas, offAccent, ww / 2, cy, nubAlpha);
        canvas.restore();
    }

    /**
     * The island, with concave fillets where it meets the edge so the bezel itself seems to grow. The
     * fillets reach further along the bezel while the island is small, like liquid leaving a wall.
     */
    private void shape(float ww, float hh, float cy, float neck) {
        float top = cy - hh / 2, bot = cy + hh / 2;
        float r = Math.min(d(RADIUS), Math.min(ww * 0.55f, hh / 2));
        float fx = Math.min(d(FILLET), ww * 0.45f), fy = fx + neck * d(H) * 0.3f;
        float k = 0.552f;
        shape.rewind();
        shape.moveTo(-d(2), top - fy);
        shape.lineTo(0, top - fy);
        shape.cubicTo(0, top - fy * 0.45f, fx * 0.45f, top, fx, top);
        shape.lineTo(ww - r, top);
        shape.cubicTo(ww - r + r * k, top, ww, top + r - r * k, ww, top + r);
        shape.lineTo(ww, bot - r);
        shape.cubicTo(ww, bot - r + r * k, ww - r + r * k, bot, ww - r, bot);
        shape.lineTo(fx, bot);
        shape.cubicTo(fx * 0.45f, bot, 0, bot + fy * 0.45f, 0, bot + fy);
        shape.lineTo(-d(2), bot + fy);
        shape.close();
    }

    /** Counter above a dial of one tick per volume step that scrolls past a fixed centre mark. */
    private void drawContent(Canvas canvas, float left, float top, float alpha) {
        float cx = left + d(W) / 2;
        counter(canvas, counter.x, cx, top + d(NUMBER_Y), alpha * (muted ? 0.35f : 1));

        float r0 = top + d(DIAL_TOP), r1 = top + d(H - DIAL_BOTTOM), yc = (r0 + r1) / 2, half = (r1 - r0) / 2;
        float gap = d(TICK_GAP), v = dial.x;
        line.setColor(HomeView.INK);
        line.setStrokeWidth(d(1.1f));
        int first = Math.max(min, (int) Math.floor(v - half / gap)), last = Math.min(max, (int) Math.ceil(v + half / gap));
        for (int i = first; i <= last; i++) {
            float y = yc - (i - v) * gap, dist = Math.abs(y - yc) / half;
            if (dist > 1) continue;
            float len = d(i % 10 == 0 ? 14 : i % 5 == 0 ? 9 : 5) / 2;
            float fade = (1 - dist) * (1 - dist);
            line.setAlpha(Math.round(255 * alpha * fade * (i <= v + 0.01f ? 0.95f : 0.3f)));
            canvas.drawLine(cx - len, y, cx + len, y, line);
        }
        line.setColor(muted ? HomeView.ACCENT : HomeView.INK);
        line.setAlpha(Math.round(255 * alpha));
        line.setStrokeWidth(d(2));
        canvas.drawLine(cx - d(12), yc, cx - d(8.5f), yc, line);
        canvas.drawLine(cx + d(8.5f), yc, cx + d(12), yc, line);
    }

    /**
     * Odometer for a continuous value: each digit rolls by the fraction of the step in progress, and a
     * higher digit rolls only while every digit below it shows 9. Fast presses keep one rolling motion.
     */
    private void counter(Canvas canvas, float v, float cx, float mid, float alpha) {
        v = Math.max(0, Math.min(999, v));
        int whole = (int) v;
        float frac = v - whole, roll = d(NUMBER) * 0.62f;
        float tens = clamp(v - 9), hundreds = clamp(v - 99);
        float right = cx + (1 + tens + hundreds) * digitWidth / 2;
        for (int place = 0, unit = 1; place < 3; place++, unit *= 10) {
            float shown = place == 0 ? 1 : place == 1 ? tens : hundreds;
            float x = right - digitWidth / 2;
            right -= digitWidth * shown;
            if (shown <= 0) continue;
            int digit = whole / unit % 10;
            float f = whole % unit == unit - 1 ? frac : 0;
            digit(canvas, digit, x, mid - f * roll + numberShift, alpha * shown * (1 - f));
            if (f > 0) digit(canvas, (digit + 1) % 10, x, mid + (1 - f) * roll + numberShift, alpha * shown * f);
        }
    }

    private void digit(Canvas canvas, int digit, float x, float baseline, float alpha) {
        if (alpha <= 0) return;
        text.setAlpha(Math.round(255 * alpha));
        canvas.drawText(DIGITS, digit, 1, x, baseline, text);
    }

    private void icon(Canvas canvas, Drawable icon, float cx, float cy, float alpha) {
        int half = Math.round(d(12) / 2), x = Math.round(cx), y = Math.round(cy);
        icon.setBounds(x - half, y - half, x + half, y + half);
        icon.setAlpha(Math.round(255 * alpha));
        icon.draw(canvas);
    }
}
