package co.pixelwood.pasta;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view and drawing helpers. No AndroidX, no third-party UI kit. */
final class Ui {
    private static Typeface serif;
    private static Typeface serifBold;
    private static Typeface serifItalic;
    private static Typeface sans;
    private static Typeface sansMedium;
    private static Typeface sansBold;

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static Typeface serif(Context context) {
        if (serif == null) {
            try {
                serif = Typeface.createFromAsset(context.getAssets(), "fonts/Fraunces.ttf");
            } catch (Exception ignored) {
                serif = Typeface.SERIF;
            }
        }
        return serif;
    }

    static Typeface serifBold(Context context) {
        if (serifBold == null) serifBold = Typeface.create(serif(context), Typeface.BOLD);
        return serifBold;
    }

    static Typeface serifItalic(Context context) {
        if (serifItalic == null) serifItalic = Typeface.create(serif(context), Typeface.ITALIC);
        return serifItalic;
    }

    static Typeface sans(Context context) {
        if (sans == null) {
            try {
                sans = Typeface.createFromAsset(context.getAssets(), "fonts/DMSans.ttf");
            } catch (Exception ignored) {
                sans = Typeface.SANS_SERIF;
            }
        }
        return sans;
    }

    static Typeface sansMedium(Context context) {
        if (sansMedium == null) sansMedium = Typeface.create(sans(context), Typeface.BOLD);
        return sansMedium;
    }

    static Typeface sansBold(Context context) {
        if (sansBold == null) sansBold = Typeface.create(sans(context), Typeface.BOLD);
        return sansBold;
    }

    static TextView text(Context context, CharSequence value, float sizeSp, int color, Typeface face) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0f, 1.18f);
        return view;
    }

    static TextView label(Context context, CharSequence value, float sizeSp, int color) {
        return text(context, value, sizeSp, color, sansMedium(context));
    }

    static GradientDrawable background(int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(App.context, radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(App.context, strokeDp), strokeColor);
        return drawable;
    }

    static Drawable ripple(Context context, int rippleColor, Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    static GradientDrawable pill(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(App.context, radiusDp));
        return drawable;
    }

    static TextView pillButton(Context context, int iconRes, String label, boolean primary) {
        int fg = primary ? Color.WHITE : App.INK_SOFT;
        TextView button = text(context, label, 11f, fg, sansMedium(context));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 34));
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setSingleLine(true);
        if (iconRes != 0) {
            Drawable icon = context.getDrawable(iconRes);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(primary ? Color.WHITE : App.MUTED);
                icon.setBounds(0, 0, dp(context, 14), dp(context, 14));
                button.setCompoundDrawablesRelative(icon, null, null, null);
                button.setCompoundDrawablePadding(dp(context, 6));
            }
        }
        if (primary) {
            button.setBackground(ripple(context, 0x33FFFFFF, background(App.ACCENT, 10, App.ACCENT_DEEP, 1)));
        } else {
            button.setBackground(ripple(context, 0x22D95138, background(App.CARD, 9, App.LINE, 1)));
        }
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    static ImageButton iconButton(Context context, int iconRes, String description, int tint) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(iconRes);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        button.setColorFilter(tint);
        button.setBackground(selectableBorderless(context));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(context, 38), dp(context, 38));
        button.setLayoutParams(lp);
        return button;
    }

    static Drawable selectableBorderless(Context context) {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
        return context.getDrawable(outValue.resourceId);
    }

    static void styleField(Context context, EditText field) {
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        field.setTypeface(sans(context));
        field.setTextColor(App.INK);
        field.setHintTextColor(App.MUTED_LIGHT);
        field.setPadding(dp(context, 13), dp(context, 12), dp(context, 13), dp(context, 12));
        field.setBackground(background(App.CARD, 10, App.LINE, 1));
        field.setSingleLine(true);
        field.setOnFocusChangeListener((v, focused) -> field.setBackground(
                background(App.CARD, 10, focused ? App.ACCENT : App.LINE, focused ? 1.5f : 1f)));
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    static LinearLayout.LayoutParams lp(int width, int height, float weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    static void margins(View view, int leftDp, int topDp, int rightDp, int bottomDp) {
        if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.setMargins(dp(App.context, leftDp), dp(App.context, topDp), dp(App.context, rightDp), dp(App.context, bottomDp));
        view.setLayoutParams(params);
    }

    static void spacer(LinearLayout parent, Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
        parent.addView(view);
    }

    static TextView dot(Context context) {
        TextView dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        dot.setTextColor(App.SAGE);
        dot.setIncludeFontPadding(false);
        return dot;
    }

    static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    static boolean isBlank(CharSequence value) {
        return TextUtils.isEmpty(value) || value.toString().trim().isEmpty();
    }
}
