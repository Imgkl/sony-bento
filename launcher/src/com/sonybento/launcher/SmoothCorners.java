package com.sonybento.launcher;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * iOS-style continuous ("squircle") rounded rectangles. Each corner is a circular arc eased in and out
 * by two cubic curves, following Figma's corner-smoothing construction (smoothing 0.6 matches iOS).
 */
final class SmoothCorners {

    private SmoothCorners() {
    }

    static Path rect(RectF r, float radius, float smoothing) {
        // The smoothed corner reaches (1 + smoothing) * radius along each edge; keep it inside half the side.
        float p = Math.min((1 + smoothing) * radius, Math.min(r.width(), r.height()) / 2);
        if (p < (1 + smoothing) * radius) smoothing = Math.max(0, p / radius - 1);

        double arcMeasure = 90 * (1 - smoothing);
        float arcSection = (float) (Math.sin(Math.toRadians(arcMeasure / 2)) * radius * Math.sqrt(2));
        double alpha = (90 - arcMeasure) / 2;
        double p3ToP4 = radius * Math.tan(Math.toRadians(alpha / 2));
        double beta = 45 * smoothing;
        float c = (float) (p3ToP4 * Math.cos(Math.toRadians(beta)));
        float d = (float) (c * Math.tan(Math.toRadians(beta)));
        float b = (p - arcSection - c - d) / 3;
        float a = 2 * b;

        Path path = new Path();
        path.moveTo(r.left + p, r.top);
        // Top-right, bottom-right, bottom-left, top-left: (start point, edge direction u, inward direction v).
        corner(path, r.right - p, r.top, 1, 0, 0, 1, -90, r, radius, p, a, b, c, d, alpha, arcMeasure);
        corner(path, r.right, r.bottom - p, 0, 1, -1, 0, 0, r, radius, p, a, b, c, d, alpha, arcMeasure);
        corner(path, r.left + p, r.bottom, -1, 0, 0, -1, 90, r, radius, p, a, b, c, d, alpha, arcMeasure);
        corner(path, r.left, r.top + p, 0, -1, 1, 0, 180, r, radius, p, a, b, c, d, alpha, arcMeasure);
        path.close();
        return path;
    }

    private static void corner(Path path, float sx, float sy, float ux, float uy, float vx, float vy, float edgeAngle,
                               RectF r, float radius, float p, float a, float b, float c, float d,
                               double alpha, double arcMeasure) {
        path.lineTo(sx, sy);
        path.cubicTo(sx + ux * a, sy + uy * a,
                sx + ux * (a + b), sy + uy * (a + b),
                sx + ux * (a + b + c) + vx * d, sy + uy * (a + b + c) + vy * d);
        float cx = sx + ux * (p - radius) + vx * radius, cy = sy + uy * (p - radius) + vy * radius;
        path.arcTo(new RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                (float) (edgeAngle + alpha), (float) arcMeasure, false);
        float ex = sx + ux * p + vx * p, ey = sy + uy * p + vy * p;
        float[] cur = currentPoint(cx, cy, radius, edgeAngle + alpha + arcMeasure);
        path.cubicTo(cur[0] + ux * d + vx * c, cur[1] + uy * d + vy * c,
                cur[0] + ux * d + vx * (b + c), cur[1] + uy * d + vy * (b + c),
                ex, ey);
    }

    private static float[] currentPoint(float cx, float cy, float radius, double angle) {
        double rad = Math.toRadians(angle);
        return new float[] {(float) (cx + radius * Math.cos(rad)), (float) (cy + radius * Math.sin(rad))};
    }
}
