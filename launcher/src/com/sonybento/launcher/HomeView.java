package com.sonybento.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bento home screen (design/v3/bento3.html) on a 960 x 540 dp TV: a 6 x 3 grid inside 48/36 dp
 * margins. The clock and Today blocks are information only; Wi-Fi, Remote and the input row take focus.
 */
public class HomeView extends View {

    static final int BG = 0xFF0E0E0F;
    static final int TILE = 0xFF1A1A1C;
    static final int ACCENT = 0xFFFF5B1F;
    static final int ON_ACCENT = 0xFF140600;
    static final int INK = 0xFFF4F3EF;
    static final int LABEL = 0xFF8A8A8E;
    static final int MUTED = 0xFF9A9A9E;
    static final int BAR_OFF = 0xFF3A3A3D;
    static final int DOT_OFF = 0xFF5A5A5E;
    static final int LIVE = 0xFF34C759;
    static final int FOCUS_MUTED = 0xFF55555A;
    static final int FOCUS_BAR_OFF = 0xFFC9C8C4;
    /** iOS continuous-corner smoothing. */
    static final float SMOOTHING = 0.9f;

    /** What a focusable tile does when chosen. */
    enum Kind { WIFI, REMOTE, ITEM }

    /** One tile in the bottom row: an HDMI input or Settings. */
    static final class Item {
        final String name;
        final String detail;
        final int icon;
        final boolean showsState;
        /** True when the device is on and sending a picture. */
        final boolean live;

        Item(String name, String detail, int icon, boolean showsState, boolean live) {
            this.name = name;
            this.detail = detail;
            this.icon = icon;
            this.showsState = showsState;
            this.live = live;
        }
    }

    /** Snapshot of the connection status. */
    static final class Status {
        String wifiName = "Wi-Fi";
        String wifiDetail = "Not connected";
        int wifiBars;
        boolean wifiOn;
        String btName = "Remote";
        String btDetail = "Not connected";
        boolean btOn;
    }

    private static final class Target {
        final Kind kind;
        final int index;
        final RectF rect;

        Target(Kind kind, int index, RectF rect) {
            this.kind = kind;
            this.index = index;
            this.rect = rect;
        }
    }

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular, bold, black;

    private String time = "", weekday = "", date = "";
    private Status status = new Status();
    private List<Item> items = Collections.emptyList();
    private final List<Target> targets = new ArrayList<>();
    private int focused = -1;

    public HomeView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        regular = font(context, "Satoshi-Regular.ttf");
        bold = font(context, "Satoshi-Bold.ttf");
        black = font(context, "Satoshi-Black.ttf");
        stroke.setStyle(Paint.Style.STROKE);
        setBackgroundColor(BG);
    }

    private static Typeface font(Context context, String file) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/" + file);
        } catch (RuntimeException e) {
            return Typeface.SANS_SERIF;
        }
    }

    void setTime(String time, String weekday, String date) {
        this.time = time;
        this.weekday = weekday;
        this.date = date;
        invalidate();
    }

    void setStatus(Status status) {
        this.status = status;
        invalidate();
    }

    void setItems(List<Item> items) {
        this.items = items;
        layoutTargets();
        if (focused < 0 || focused >= targets.size()) focusFirstItem();
        invalidate();
    }

    void focusFirstItem() {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).kind == Kind.ITEM) {
                focused = i;
                break;
            }
        }
        invalidate();
    }

    void focusItem(int itemIndex) {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).kind == Kind.ITEM && targets.get(i).index == itemIndex) {
                focused = i;
                break;
            }
        }
        invalidate();
    }

    Kind focusedKind() {
        return focused >= 0 && focused < targets.size() ? targets.get(focused).kind : null;
    }

    int focusedItem() {
        return focused >= 0 && focused < targets.size() ? targets.get(focused).index : -1;
    }

    /** Moves focus to the nearest focusable tile in the D-pad direction; returns false if there is none. */
    boolean move(int keyCode) {
        if (focused < 0) return false;
        RectF from = targets.get(focused).rect;
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
            if (i == focused) continue;
            RectF to = targets.get(i).rect;
            float dx = to.centerX() - from.centerX(), dy = to.centerY() - from.centerY();
            float along, across;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: along = to.left - from.right; across = Math.abs(dy); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: along = from.left - to.right; across = Math.abs(dy); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: along = to.top - from.bottom; across = Math.abs(dx); break;
                case KeyEvent.KEYCODE_DPAD_UP: along = from.top - to.bottom; across = Math.abs(dx); break;
                default: return false;
            }
            if (along < -1) continue;
            float score = along + 2 * across;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) return false;
        focused = best;
        invalidate();
        return true;
    }

    // Grid: 6 columns of 134 dp and 3 rows of 148 dp with 12 dp gaps, starting at (48, 36).
    private RectF cell(int col, int row, int cols, int rows) {
        float x = d(48 + col * 146), y = d(36 + row * 160);
        return new RectF(x, y, x + d(cols * 134 + (cols - 1) * 12), y + d(rows * 148 + (rows - 1) * 12));
    }

    private void layoutTargets() {
        targets.clear();
        targets.add(new Target(Kind.WIFI, -1, cell(3, 0, 2, 1)));
        targets.add(new Target(Kind.REMOTE, -1, cell(5, 0, 1, 1)));
        int n = items.size();
        if (n == 0) return;
        // Three tiles span two columns each; more tiles share the six columns evenly.
        float width = (864 - (n - 1) * 12f) / n;
        for (int i = 0; i < n; i++) {
            float x = d(48 + i * (width + 12)), y = d(36 + 2 * 160);
            targets.add(new Target(Kind.ITEM, i, new RectF(x, y, x + d(width), y + d(148))));
        }
    }

    private float d(float value) {
        return value * dp;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (targets.isEmpty()) layoutTargets();
        drawClock(canvas, cell(0, 0, 3, 2));
        drawToday(canvas, cell(3, 1, 3, 1));
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            boolean on = i == focused;
            tile(canvas, t.rect, on ? INK : TILE);
            if (on) {
                stroke.setColor(INK);
                stroke.setStrokeWidth(d(3));
                float o = d(5.5f);
                RectF ring = new RectF(t.rect.left - o, t.rect.top - o, t.rect.right + o, t.rect.bottom + o);
                canvas.drawPath(SmoothCorners.rect(ring, d(29.5f), SMOOTHING), stroke);
            }
            switch (t.kind) {
                case WIFI: drawWifi(canvas, t.rect, on); break;
                case REMOTE: drawRemote(canvas, t.rect, on); break;
                default: drawItem(canvas, t.rect, items.get(t.index), on); break;
            }
        }
    }

    private void tile(Canvas canvas, RectF r, int color) {
        fill.setColor(color);
        canvas.drawPath(SmoothCorners.rect(r, d(24), SMOOTHING), fill);
    }

    private void drawClock(Canvas canvas, RectF r) {
        tile(canvas, r, ACCENT);
        drawText(canvas, "Now", r.left + d(26), r.top + d(20) + d(11), 16, bold, (ON_ACCENT & 0x00FFFFFF) | 0xCC000000, 0);
        text.setTypeface(black);
        text.setTextSize(d(150));
        text.setLetterSpacing(-0.06f);
        text.setFontFeatureSettings("tnum");
        text.setColor(ON_ACCENT);
        text.setTextAlign(Paint.Align.LEFT);
        // 150 dp fits "9:40"; a two-digit hour such as "10:44" shrinks to keep 26 dp padding on both sides.
        float size = Math.min(d(150), d(150) * (r.width() - d(52)) / text.measureText(time));
        text.setTextSize(size);
        Paint.FontMetrics m = text.getFontMetrics();
        float lineHeight = size * 0.86f, lineTop = r.bottom - d(20) - lineHeight;
        canvas.drawText(time, r.left + d(26), lineTop + (lineHeight - (m.descent - m.ascent)) / 2 - m.ascent, text);
        text.setLetterSpacing(0);
        text.setFontFeatureSettings(null);
    }

    private void drawToday(Canvas canvas, RectF r) {
        tile(canvas, r, TILE);
        label(canvas, "TODAY", r, LABEL);
        text.setTypeface(black);
        text.setTextSize(d(38));
        text.setLetterSpacing(-0.03f);
        text.setColor(INK);
        text.setTextAlign(Paint.Align.LEFT);
        float baseline = r.bottom - d(16) - d(7);
        canvas.drawText(weekday, r.left + d(20), baseline, text);
        text.setLetterSpacing(0);
        text.setTypeface(bold);
        text.setTextSize(d(21));
        text.setColor(MUTED);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(date, r.right - d(20), baseline, text);
    }

    private void label(Canvas canvas, String value, RectF r, int color) {
        text.setTypeface(bold);
        text.setTextSize(d(12));
        text.setLetterSpacing(0.08f);
        text.setColor(color);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(value, r.left + d(20), r.top + d(16) + d(12), text);
        text.setLetterSpacing(0);
    }

    private void drawWifi(Canvas canvas, RectF r, boolean on) {
        label(canvas, "WI-FI", r, on ? FOCUS_MUTED : LABEL);
        float detailBase = r.bottom - d(16) - d(4);
        drawText(canvas, status.wifiDetail, r.left + d(20), detailBase - d(5), 13, regular, on ? FOCUS_MUTED : MUTED, 0);
        drawText(canvas, status.wifiName, r.left + d(20), detailBase - d(27), 21, bold, on ? BG : INK, 0);
        // Four bars, 7 dp wide and 4 dp apart, 7/12/17/22 dp tall, bottom-right.
        float right = r.right - d(20), bottom = r.bottom - d(16) - d(4);
        for (int i = 0; i < 4; i++) {
            float x = right - d((3 - i) * 11 + 7);
            boolean lit = status.wifiOn && i < status.wifiBars;
            fill.setColor(lit ? (on ? BG : INK) : (on ? FOCUS_BAR_OFF : BAR_OFF));
            canvas.drawRoundRect(x, bottom - d(7 + i * 5), x + d(7), bottom, d(2), d(2), fill);
        }
    }

    private void drawRemote(Canvas canvas, RectF r, boolean on) {
        label(canvas, "REMOTE", r, on ? FOCUS_MUTED : LABEL);
        float detailBase = r.bottom - d(16) - d(4);
        drawText(canvas, status.btOn ? "Connected" : "Not connected", r.left + d(20), detailBase - d(5), 13, regular, on ? FOCUS_MUTED : MUTED, 0);
        // A 52 x 24 dp remote, solid when connected and an outline when not.
        float left = r.left + d(20), bottom = detailBase - d(25);
        RectF body = new RectF(left, bottom - d(24), left + d(52), bottom);
        int ink = on ? BG : INK, hole = on ? INK : TILE;
        if (status.btOn) {
            fill.setColor(ink);
            canvas.drawRoundRect(body, d(8), d(8), fill);
        } else {
            stroke.setColor(ink);
            stroke.setStrokeWidth(d(2));
            canvas.drawRoundRect(body.left + d(1), body.top + d(1), body.right - d(1), body.bottom - d(1), d(7), d(7), stroke);
        }
        fill.setColor(status.btOn ? hole : ink);
        canvas.drawCircle(body.left + d(41), body.centerY(), d(4.6f), fill);
        for (int c = 0; c < 2; c++) {
            for (int rr = 0; rr < 2; rr++) {
                canvas.drawCircle(body.left + d(11 + c * 6.5f), body.top + d(8 + rr * 8), d(1.9f), fill);
            }
        }
    }

    private void drawItem(Canvas canvas, RectF r, Item item, boolean on) {
        if (item.icon != 0) {
            Drawable icon = getContext().getDrawable(item.icon).mutate();
            int s = Math.round(d(38));
            int x = Math.round(r.left + d(20)), y = Math.round(r.top + d(16));
            icon.setBounds(x, y, x + s, y + s);
            icon.setTint(on ? BG : INK);
            icon.draw(canvas);
        }
        float detailBase = r.bottom - d(16) - d(4);
        float x = r.left + d(20);
        if (item.showsState) {
            fill.setColor(item.live ? LIVE : (on ? FOCUS_MUTED : DOT_OFF));
            canvas.drawCircle(x + d(3.5f), detailBase - d(4.5f), d(3.5f), fill);
            x += d(14);
        }
        drawText(canvas, item.detail, x, detailBase, 14, regular, on ? FOCUS_MUTED : MUTED, 0);
        String name = fit(item.name, r.width() - d(40), 24, bold);
        drawText(canvas, name, r.left + d(20), detailBase - d(26), 24, bold, on ? BG : INK, -0.01f);
    }

    /** Draws single-line text on a baseline; returns the x where it ends. */
    private float drawText(Canvas canvas, String value, float x, float baseline, float sizeDp, Typeface face, int color, float tracking) {
        text.setTypeface(face);
        text.setTextSize(d(sizeDp));
        text.setLetterSpacing(tracking);
        text.setColor(color);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(value, x, baseline, text);
        float end = x + text.measureText(value);
        text.setLetterSpacing(0);
        return end;
    }

    /** Shortens text with an ellipsis so it never wraps or overflows its tile. */
    private String fit(String value, float max, float sizeDp, Typeface face) {
        text.setTypeface(face);
        text.setTextSize(d(sizeDp));
        if (text.measureText(value) <= max) return value;
        String cut = value;
        while (cut.length() > 1 && text.measureText(cut + "…") > max) cut = cut.substring(0, cut.length() - 1);
        return cut.trim() + "…";
    }
}
