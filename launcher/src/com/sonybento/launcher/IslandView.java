package com.sonybento.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.View;

/**
 * Volume indicator that grows out of the left bezel as a black island, in place of the stock bar that
 * covers subtitles. Sizes are dp. The level shown is always read back from AudioManager.
 */
final class IslandView extends View {

    private static final float W = 34, H = 130, RADIUS = 17, FILLET = 15, NW = 22, NH = 32;
    private static final float PAD = 10, NUMBER = 15, ICON = 12, TRACK = 3, GAP = 8;
    private static final long SHOW_MS = 2000, PINCH_WIDTH_DELAY_MS = 90, DIGIT_MS = 340, SLOT_MS = 320;

    enum State { HIDDEN, FULL, NUB }

    /** Damped spring in dp, stepped with semi-implicit Euler. */
    static final class Spring {
        float x, v, target;
        final float k, c;

        Spring(float k, float c) {
            this.k = k;
            this.c = c;
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

    /** One digit position of the counter. A space is an empty slot. */
    static final class Slot {
        char now = ' ', was = ' ';
        long changed;
        int dir;
        float widthFrom, widthTo;
    }

    private final float dp;
    private final AudioManager audio;
    private final int max;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Drawable loud, quiet, off, offAccent;
    private final float digitWidth, numberShift;
    private final Spring w = new Spring(420, 24), h = new Spring(240, 15), lv = new Spring(300, 26);
    private final Spring[] springs = {w, h, lv};
    /** Ones, tens, hundreds. */
    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private final Runnable dismiss = this::dismiss;
    private final Runnable pinchWidth = () -> {
        w.target = NW;
        loop();
    };

    private State state = State.HIDDEN;
    private int level;
    private boolean muted;
    private boolean animating;
    private long lastFrame;

    IslandView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        audio = context.getSystemService(AudioManager.class);
        max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        text.setTypeface(HomeView.font(context, "Satoshi-Black.ttf"));
        text.setTextSize(d(NUMBER));
        text.setFontFeatureSettings("tnum");
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(HomeView.INK);
        Paint.FontMetrics m = text.getFontMetrics();
        numberShift = -(m.ascent + m.descent) / 2;
        digitWidth = text.measureText("0");
        loud = tinted(context, R.drawable.ic_volume, HomeView.INK);
        quiet = tinted(context, R.drawable.ic_volume_2, HomeView.INK);
        off = tinted(context, R.drawable.ic_volume_3, HomeView.INK);
        offAccent = tinted(context, R.drawable.ic_volume_3, HomeView.ACCENT);
        h.x = h.target = H * 0.6f;
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        digits(level, 0, 0);
        sync();
        lv.x = lv.target;
        if (muted) pinch();
    }

    private static Drawable tinted(Context context, int id, int color) {
        Drawable icon = context.getDrawable(id).mutate();
        icon.setTint(color);
        return icon;
    }

    /** A volume change: dir is 1 for up and -1 for down. */
    void press(int dir) {
        if (state == State.HIDDEN) w.v = Math.max(w.v, W * 2.8f);
        else if (state == State.NUB) w.v = Math.max(w.v, W * 1.6f);
        show();
        h.v += dir * H * 0.45f;
        w.v += W * 0.75f;
        sync();
    }

    /** A mute or unmute. The island pinches into the nub on dismiss while muted, and hides otherwise. */
    void pressMute() {
        show();
        h.v -= H * 1.1f;
        w.v += W * 1.5f;
        sync();
    }

    private void sync() {
        int next = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (next != level) digits(next, next > level ? 1 : -1, SystemClock.uptimeMillis());
        level = next;
        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        lv.target = muted ? 0 : level * 100f / max;
        loop();
    }

    private void show() {
        state = State.FULL;
        w.target = W;
        h.target = H;
        removeCallbacks(pinchWidth);
        removeCallbacks(dismiss);
        postDelayed(dismiss, SHOW_MS);
        loop();
    }

    private void dismiss() {
        if (muted) {
            pinch();
            return;
        }
        state = State.HIDDEN;
        w.target = 0;
        h.target = H * 0.6f;
        loop();
    }

    /** Height pulls in first and the width follows, so the island squeezes into the mute nub. */
    private void pinch() {
        state = State.NUB;
        h.target = NH;
        h.v -= H * 0.4f;
        postDelayed(pinchWidth, PINCH_WIDTH_DELAY_MS);
        loop();
    }

    /** Rolls the counter to n. Only the digits that change move, up for dir 1 and down for dir -1. */
    private void digits(int n, int dir, long at) {
        String s = Integer.toString(n);
        for (int i = 0; i < slots.length; i++) {
            Slot slot = slots[i];
            char c = i < s.length() ? s.charAt(s.length() - 1 - i) : ' ';
            if (c == slot.now) continue;
            slot.widthFrom = slotWidth(slot, at);
            slot.widthTo = c == ' ' ? 0 : digitWidth;
            slot.was = slot.now;
            slot.now = c;
            slot.dir = dir;
            slot.changed = at;
        }
    }

    private float slotWidth(Slot slot, long now) {
        return slot.widthFrom + (slot.widthTo - slot.widthFrom) * easeOut((now - slot.changed) / (float) SLOT_MS);
    }

    private void loop() {
        if (animating) return;
        animating = true;
        lastFrame = SystemClock.uptimeMillis();
        postOnAnimation(this::frame);
    }

    private void frame() {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(now - lastFrame, 32) / 2000f; // two substeps per frame
        lastFrame = now;
        boolean moving = false;
        for (Spring s : springs) {
            s.step(dt);
            s.step(dt);
            moving |= !s.settle();
        }
        for (Slot s : slots) moving |= now - s.changed < DIGIT_MS;
        invalidate();
        if (moving) postOnAnimation(this::frame);
        else animating = false;
    }

    private float d(float value) {
        return value * dp;
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static float easeOut(float t) {
        float u = 1 - clamp(t);
        return 1 - u * u * u;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (w.x < 0.3f) return;
        float ww = d(w.x), cy = getHeight() / 2f;
        shape(ww, d(h.x), cy);
        fill.setColor(0xFF000000);
        canvas.drawPath(shape, fill);
        float nub = state == State.NUB ? clamp((H - h.x) / (H - NH)) : 0;
        canvas.save();
        canvas.clipPath(shape);
        float alpha = clamp((w.x / W - 0.55f) / 0.4f) * (1 - clamp(nub * 1.6f));
        if (alpha > 0) drawContent(canvas, -(d(W) - ww) * 0.45f * (1 - nub), cy - d(H) / 2, alpha);
        float nubAlpha = clamp((nub - 0.45f) / 0.4f) * clamp(w.x / (NW * 0.7f));
        if (nubAlpha > 0) icon(canvas, offAccent, ww / 2, cy, nubAlpha);
        canvas.restore();
    }

    /** The island, with concave fillets where it meets the edge so the bezel itself seems to grow. */
    private void shape(float ww, float hh, float cy) {
        float top = cy - hh / 2, bot = cy + hh / 2;
        float r = Math.min(d(RADIUS), Math.min(ww * 0.62f, hh / 2));
        float f = Math.min(d(FILLET), ww * 0.55f);
        float k = 0.55f;
        shape.rewind();
        shape.moveTo(-d(2), top - f);
        shape.lineTo(0, top - f);
        shape.cubicTo(0, top - f * 0.42f, f * 0.42f, top, f, top);
        shape.lineTo(ww - r, top);
        shape.cubicTo(ww - r + r * k, top, ww, top + r - r * k, ww, top + r);
        shape.lineTo(ww, bot - r);
        shape.cubicTo(ww, bot - r + r * k, ww - r + r * k, bot, ww - r, bot);
        shape.lineTo(f, bot);
        shape.cubicTo(f * 0.42f, bot, 0, bot + f * 0.42f, 0, bot + f);
        shape.lineTo(-d(2), bot + f);
        shape.close();
    }

    /** Number, level track and speaker, laid out at full island size from (left, top). */
    private void drawContent(Canvas canvas, float left, float top, float alpha) {
        float cx = left + d(W) / 2;
        long now = SystemClock.uptimeMillis();
        float total = 0;
        for (Slot s : slots) total += slotWidth(s, now);
        float right = cx + total / 2, baseline = top + d(PAD + NUMBER / 2) + numberShift;
        float numberAlpha = alpha * (muted ? 0.3f : 1);
        for (Slot s : slots) {
            float width = slotWidth(s, now), x = right - width / 2;
            right -= width;
            float e = easeOut((now - s.changed) / (float) DIGIT_MS), shift = d(NUMBER) * 0.55f * s.dir;
            digit(canvas, s.was, x, baseline - shift * e, numberAlpha * (1 - e));
            digit(canvas, s.now, x, baseline + shift * (1 - e), numberAlpha * e);
        }

        float trackTop = top + d(PAD + NUMBER + GAP), trackBottom = top + d(H - PAD - ICON - GAP), half = d(TRACK) / 2;
        fill.setColor(HomeView.INK);
        fill.setAlpha(Math.round(255 * 0.16f * alpha));
        canvas.drawRoundRect(cx - half, trackTop, cx + half, trackBottom, half, half, fill);
        float filled = clamp(lv.x / 100);
        if (filled > 0) {
            fill.setAlpha(Math.round(255 * alpha));
            canvas.drawRoundRect(cx - half, trackBottom - (trackBottom - trackTop) * filled, cx + half, trackBottom, half, half, fill);
        }

        Drawable speaker = muted ? offAccent : level == 0 ? off : level * 100 >= 35 * max ? loud : quiet;
        icon(canvas, speaker, cx, top + d(H - PAD - ICON / 2), alpha);
    }

    private void digit(Canvas canvas, char c, float x, float baseline, float alpha) {
        if (c == ' ' || alpha <= 0) return;
        text.setAlpha(Math.round(255 * alpha));
        canvas.drawText(String.valueOf(c), x, baseline, text);
    }

    private void icon(Canvas canvas, Drawable icon, float cx, float cy, float alpha) {
        int half = Math.round(d(ICON) / 2), x = Math.round(cx), y = Math.round(cy);
        icon.setBounds(x - half, y - half, x + half, y + half);
        icon.setAlpha(Math.round(255 * alpha));
        icon.draw(canvas);
    }
}
