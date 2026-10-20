package com.juhyeon.chesstip;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(16, 30, 36);
    static final int CARD = Color.rgb(25, 46, 55);
    static final int LINE = Color.rgb(49, 73, 82);
    static final int TEXT = Color.rgb(240, 248, 250);
    static final int MUTED = Color.rgb(162, 185, 195);
    static final int GREEN = Color.rgb(176, 237, 122);
    static final int CAPTURE = Color.rgb(255, 172, 92);

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable background(int color, float radius, Context context) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(context, radius));
        return result;
    }

    static TextView text(Context context, String text, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(context, 3), 1f);
        if (bold) view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    static GradientDrawable outline(Context context, int color, int border) {
        GradientDrawable result = background(color, 12, context);
        result.setStroke(dp(context, 1), border);
        return result;
    }

    static GradientDrawable overlayButton(Context context, int color, int border, boolean filled) {
        return outline(context, (color & 0xffffff) | (filled ? 95 << 24 : 0), border);
    }

    static Button button(Context context, String text, boolean primary) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? BG : TEXT);
        button.setBackground(background(primary ? GREEN : CARD, 16, context));
        button.setMinHeight(dp(context, 54));
        button.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
        button.setStateListAnimator(null);
        return button;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(parent.getContext(), topMargin);
        parent.addView(child, params);
    }

    static void insets(Activity activity, View root, int horizontal, int vertical) {
        activity.getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets system = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(activity, horizontal) + system.left,
                    dp(activity, vertical) + system.top,
                    dp(activity, horizontal) + system.right,
                    dp(activity, vertical) + system.bottom);
            return insets;
        });
    }
}
