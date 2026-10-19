package co.pixelwood.pasta;

import android.app.Application;
import android.content.Context;
import android.graphics.Color;

/** Palette and a single application context used by the hand-rolled views. */
public final class App extends Application {
    static Context context;

    static final int PAPER = Color.rgb(242, 237, 228);
    static final int PAPER_SOFT = Color.rgb(248, 243, 234);
    static final int CARD = Color.rgb(255, 253, 248);
    static final int INK = Color.rgb(29, 26, 21);
    static final int INK_SOFT = Color.rgb(59, 53, 44);
    static final int MUTED = Color.rgb(119, 109, 94);
    static final int MUTED_LIGHT = Color.rgb(154, 142, 125);
    static final int LINE = Color.rgb(222, 212, 196);
    static final int LINE_STRONG = Color.rgb(201, 187, 166);
    static final int ACCENT = Color.rgb(217, 81, 56);
    static final int ACCENT_DEEP = Color.rgb(185, 61, 38);
    static final int ACCENT_SOFT = Color.rgb(248, 222, 213);
    static final int FOREST = Color.rgb(29, 58, 49);
    static final int SAGE = Color.rgb(111, 139, 121);
    static final int GOLD = Color.rgb(237, 181, 95);
    static final int DANGER = Color.rgb(184, 58, 43);
    static final int GREEN_SOFT = Color.rgb(232, 240, 231);
    static final int GREEN_INK = Color.rgb(71, 112, 82);
    static final int WHITE = Color.WHITE;

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
    }
}
