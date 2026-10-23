package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;


/**
 * Official coin logos (BTC, ETH, SOL, USDT), flat and single-color: just the symbol, no disc or gradient
 * (res/drawable/coin_*.xml, vector drawables from the open cryptocurrency-icons set, CC0). The four symbols were
 * drawn on the same 32-unit frame with balanced visual "weight", so here they all use the same frame scale and only
 * the spare space on the sides is trimmed. Each size is drawn once into an exact-size bitmap and reused (sharp, no
 * resampling).
 */
final class Glyphs {
    private Glyphs() {}

    private static final SparseArray<Bitmap> BITMAPS = new SparseArray<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /** Draws the coin centered at (cx, cy), sized to the text next to it (cap height `cap`), in `color`. */
    static void coin(Context context, Canvas c, String coin, float cx, float cy, float cap, int color) {
        Bitmap b = bitmap(context, coin, frame(cap));
        if (b == null) return;
        PAINT.setColor(color);
        c.drawBitmap(b, Math.round(cx - b.getWidth() / 2f), Math.round(cy - b.getHeight() / 2f), PAINT);
    }

    /** Width the symbol takes up (for laying out the line). */
    static float width(Context context, String coin, float cap) {
        Bitmap b = bitmap(context, coin, frame(cap));
        return b == null ? 0 : b.getWidth();
    }

    /** Side of the 32-unit frame: the largest symbol (the ETH diamond) ends up ~1.3 × the cap height. */
    private static int frame(float cap) {
        return Math.max(12, Math.round(cap * 1.75f));
    }

    private static Bitmap bitmap(Context context, String coin, int frame) {
        int slot = slot(coin), key = slot << 16 | frame;
        synchronized (BITMAPS) {
            if (BITMAPS.indexOfKey(key) >= 0) return BITMAPS.get(key);
            Bitmap b = null;
            if (slot >= TEXT) {
                b = text(LABELS[slot - TEXT], frame);
            } else {
                Drawable d = context.getDrawable(IDS[slot]);
                if (d != null) {
                    Bitmap full = Bitmap.createBitmap(frame, frame, Bitmap.Config.ARGB_8888);
                    d.setBounds(0, 0, frame, frame);
                    d.draw(new Canvas(full));
                    b = trimSides(full).extractAlpha();
                }
            }
            BITMAPS.put(key, b);
            return b;
        }
    }

    /** Local change: currencies and tickers have no logo here; their sign or short name, drawn the same way. */
    private static Bitmap text(String label, int frame) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextSize(frame * (label.length() == 1 ? 0.62f : 0.56f));
        int w = Math.max(1, (int) Math.ceil(p.measureText(label)) + 2);
        Bitmap full = Bitmap.createBitmap(w, frame, Bitmap.Config.ARGB_8888);
        Paint.FontMetrics m = p.getFontMetrics();
        new Canvas(full).drawText(label, 1, frame / 2f - (m.ascent + m.descent) / 2, p);
        return trimSides(full).extractAlpha();
    }

    /** Trims only the empty columns on the sides (the frame height stays, so all coins center on the same line). */
    private static Bitmap trimSides(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), left = w, right = -1;
        int[] column = new int[h];
        for (int x = 0; x < w; x++) {
            b.getPixels(column, 0, 1, x, 0, 1, h);
            for (int a : column) {
                if ((a >>> 24) > 8) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    break;
                }
            }
        }
        if (right < left) return b;
        return Bitmap.createBitmap(b, left, 0, right - left + 1, h);
    }

    private static final int[] IDS = {R.drawable.coin_btc, R.drawable.coin_eth, R.drawable.coin_sol, R.drawable.coin_usdt};

    private static final int TEXT = 4;
    private static final String[] CODES = {"USD", "EUR", "CNY", "IMOEX", "SBER", "GAZP", "LKOH", "YDEX"};
    private static final String[] LABELS = {"$", "€", "¥", "MOEX", "SBER", "GAZP", "LKOH", "YDEX"};

    private static int slot(String coin) {
        switch (coin) {
            case "BTC": return 0;
            case "ETH": return 1;
            case "SOL": return 2;
            case "USDT": return 3;
            default:
                int i = java.util.Arrays.asList(CODES).indexOf(coin);
                return i < 0 ? 3 : TEXT + i;
        }
    }
}
