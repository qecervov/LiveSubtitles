package org.livesubtitles.android;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;

final class Overlay {
    private final Context context;
    private final WindowManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean showOriginal;
    private LinearLayout panel, toolbar;
    private TextView text, original;
    private WindowManager.LayoutParams layout;
    private final Runnable hideControls = () -> {
        if (toolbar != null) { toolbar.setVisibility(View.GONE); updateVisibility(); }
    };
    private final Runnable clearCaption = () -> {
        if (text != null) {
            text.setText(""); text.setVisibility(View.GONE);
            original.setText(""); original.setVisibility(View.GONE); updateVisibility();
        }
    };

    Overlay(Context context, boolean showOriginal) {
        this.context = context; this.showOriginal = showOriginal;
        manager = context.getSystemService(WindowManager.class);
    }
    private int dp(int value) { return (int) (value * context.getResources().getDisplayMetrics().density + .5f); }

    /** Draw both passes with the same layout so wrapped outlines match the fill. */
    private final class CaptionView extends TextView {
        private boolean outlining;
        CaptionView(float size) {
            super(context); setTextSize(size); setTextColor(Color.WHITE);
            setGravity(Gravity.CENTER); setTypeface(null, Typeface.BOLD);
            setPadding(dp(4), dp(3), dp(4), dp(3));
            setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        }
        @Override public void invalidate() { if (!outlining) super.invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            int color = getCurrentTextColor();
            outlining = true; setTextColor(Color.BLACK);
            getPaint().setStyle(Paint.Style.STROKE);
            getPaint().setStrokeWidth(Math.max(dp(2), getTextSize() * .10f));
            getPaint().setStrokeJoin(Paint.Join.ROUND); super.onDraw(canvas);
            getPaint().setStyle(Paint.Style.FILL); setTextColor(color); outlining = false;
            super.onDraw(canvas);
        }
    }

    void show(int fontSize, Runnable stop, Runnable reset) {
        panel = new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL); panel.setPadding(dp(8), 0, dp(8), dp(4));
        // Transparent outside the text; controls appear only briefly or on touch.
        toolbar = new LinearLayout(context); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable background = new GradientDrawable(); background.setColor(0xB3000000);
        background.setCornerRadius(dp(12)); toolbar.setBackground(background);
        TextView title = action("↕", "Переместить субтитры", this::revealControls);
        toolbar.addView(title, new LinearLayout.LayoutParams(dp(56), dp(48)));
        toolbar.addView(action("↻", "Очистить очередь после перемотки", () -> { reset.run(); revealControls(); }), new LinearLayout.LayoutParams(dp(56), dp(48)));
        toolbar.addView(action("✕", "Остановить перевод", stop), new LinearLayout.LayoutParams(dp(56), dp(48)));
        panel.addView(toolbar);
        text = new CaptionView(fontSize); text.setMaxLines(4); text.setVisibility(View.GONE);
        text.setContentDescription("Субтитры. Нажмите для управления"); panel.addView(text);
        original = new CaptionView(Math.max(12, fontSize * .65f)); original.setMaxLines(2);
        original.setVisibility(View.GONE); panel.addView(original);
        layout = new WindowManager.LayoutParams(
            Math.max(dp(220), context.getResources().getDisplayMetrics().widthPixels - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; layout.y = dp(72);
        attachDrag(title); attachDrag(text); attachDrag(original);
        manager.addView(panel, layout); revealControls();
    }
    private void attachDrag(View view) {
        view.setOnClickListener(v -> revealControls());
        view.setOnTouchListener(new View.OnTouchListener() {
            float start; int initial; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (layout == null) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        start = event.getRawY(); initial = layout.y; moved = false;
                        main.removeCallbacks(hideControls); return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(start - event.getRawY()) > ViewConfiguration.get(context).getScaledTouchSlop()) moved = true;
                        if (moved) {
                            layout.y = Math.max(0, Math.min(Math.max(0, context.getResources().getDisplayMetrics().heightPixels - panel.getHeight()), initial + (int) (start - event.getRawY())));
                            updateLayout();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) v.performClick(); else main.postDelayed(hideControls, 3000);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.postDelayed(hideControls, 3000); return true;
                    default: return false;
                }
            }
        });
    }
    private TextView action(String label, String description, Runnable action) {
        TextView button = new TextView(context); button.setText(label); button.setTextSize(22);
        button.setTextColor(Color.WHITE); button.setGravity(Gravity.CENTER); button.setContentDescription(description);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private void revealControls() {
        if (toolbar == null) return;
        toolbar.setVisibility(View.VISIBLE); updateVisibility();
        main.removeCallbacks(hideControls); main.postDelayed(hideControls, 3000);
    }
    private void updateVisibility() {
        if (panel != null) panel.setVisibility(text.getVisibility() == View.VISIBLE || toolbar.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }
    void status(String value) { main.post(() -> {
        if (text == null) return;
        main.removeCallbacks(clearCaption); text.setText(value); text.setVisibility(View.VISIBLE);
        original.setVisibility(View.GONE); updateVisibility(); main.postDelayed(clearCaption, 2500);
    }); }
    void subtitle(String translated, String source) {
        main.post(() -> {
            if (text == null || translated.trim().isEmpty()) return;
            main.removeCallbacks(clearCaption); text.setText(translated.trim()); text.setVisibility(View.VISIBLE);
            original.setText(source.trim()); original.setVisibility(showOriginal && !source.trim().isEmpty() ? View.VISIBLE : View.GONE);
            updateVisibility(); main.postDelayed(clearCaption, Math.min(7000, Math.max(3000, translated.length() * 65L)));
        });
    }
    void close() {
        main.removeCallbacksAndMessages(null);
        if (panel != null) { try { manager.removeView(panel); } catch (IllegalArgumentException ignored) { } panel = null; }
        text = null; original = null; toolbar = null; layout = null;
    }
    private void updateLayout() {
        try { manager.updateViewLayout(panel, layout); } catch (IllegalArgumentException ignored) { }
    }
    void configurationChanged() {
        if (panel == null || layout == null) return;
        layout.width = Math.max(dp(220), context.getResources().getDisplayMetrics().widthPixels - dp(24));
        layout.y = Math.min(layout.y, Math.max(0, context.getResources().getDisplayMetrics().heightPixels - dp(180)));
        updateLayout();
    }
}
