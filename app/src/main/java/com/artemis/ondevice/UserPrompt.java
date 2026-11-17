package com.artemis.ondevice;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A card at the bottom of the screen that asks the user something during a run: confirm a
 * sensitive action, or pick an answer the agent asked for. Drawn by the accessibility service
 * (no overlay permission needed). {@link #ask} blocks the loop thread until the user picks an
 * option, the run is stopped, or the time runs out. During a voice call the card is also read
 * out, and what the user says can pick an option ({@link #pending()}).
 */
final class UserPrompt {

    static final int NO_ANSWER = -1;
    static final long TIMEOUT_MS = 3 * 60 * 1000L;

    private UserPrompt() {}

    /** The card on screen right now, so a voice call can read it out and answer it. */
    static final class Pending {
        final String message;
        final List<String> options;
        /** A sensitive-action confirmation (allow / allow for the task / don't allow), not a question. */
        final boolean confirm;
        private final AtomicInteger picked;
        private final CountDownLatch answered;

        private Pending(String message, List<String> options, boolean confirm, AtomicInteger picked, CountDownLatch answered) {
            this.message = message;
            this.options = options;
            this.confirm = confirm;
            this.picked = picked;
            this.answered = answered;
        }

        /** Picks an option for the user, from what they said; ignored once the card is answered. */
        void answer(int index) {
            if (index < 0 || index >= options.size() || answered.getCount() == 0) return;
            picked.set(index);
            answered.countDown();
        }
    }

    private static volatile Pending pending;

    static Pending pending() { return pending; }

    /**
     * Shows the card and waits. Returns the index of the option picked, or {@link #NO_ANSWER}
     * when the run was stopped or nobody answered in time. Loop thread only.
     */
    static int ask(final AgentAccessibilityService s, final String title, final String message,
                   final List<String> options, boolean confirm, FlashLoop.StopSignal stop) throws InterruptedException {
        final Handler main = new Handler(Looper.getMainLooper());
        final WindowManager wm = s.getSystemService(WindowManager.class);
        final AtomicInteger picked = new AtomicInteger(NO_ANSWER);
        final CountDownLatch answered = new CountDownLatch(1);
        final View[] card = new View[1];
        main.post(() -> {
            card[0] = build(s, title, message, options, i -> {
                if (answered.getCount() == 0) return;
                picked.set(i);
                answered.countDown();
            });
            wm.addView(card[0], params(s));
        });
        pending = new Pending(message, options, confirm, picked, answered);
        AppLog.i(AppLog.AGENT, (confirm ? "confirmation" : "question") + " shown, " + options.size() + " options");
        try {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (!answered.await(200, TimeUnit.MILLISECONDS)) {
                if (stop.stopped() || System.currentTimeMillis() > deadline) break;
            }
        } finally {
            pending = null;
            AppLog.i(AppLog.AGENT, (confirm ? "confirmation" : "question") + " closed: "
                    + (picked.get() == NO_ANSWER ? (stop.stopped() ? "run stopped" : "no answer in time") : "option " + picked.get()));
            final CountDownLatch removed = new CountDownLatch(1);
            main.post(() -> {
                if (card[0] != null && card[0].isAttachedToWindow()) wm.removeView(card[0]);
                removed.countDown();
            });
            removed.await(500, TimeUnit.MILLISECONDS);
            // Let the card's window go before the next screenshot or gesture.
            Thread.sleep(150);
        }
        return picked.get();
    }

    interface OnPick {
        void picked(int index);
    }

    private static View build(Context base, String title, String message, List<String> options, OnPick onPick) {
        Context c = new ContextThemeWrapper(base, R.style.Theme_Artemis);
        int pad = dp(c, 20);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, dp(c, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.getColor(R.color.surface_card));
        bg.setCornerRadius(dp(c, 24));
        bg.setStroke(dp(c, 2), c.getColor(R.color.brand));
        box.setBackground(bg);
        box.setElevation(dp(c, 12));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        t.setTextColor(c.getColor(R.color.brand));
        box.addView(t);

        TextView m = new TextView(c);
        m.setText(message);
        m.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        m.setTextColor(c.getColor(R.color.text_primary));
        m.setPadding(0, dp(c, 8), 0, dp(c, 12));
        box.addView(m);

        for (int i = 0; i < options.size(); i++) {
            final int index = i;
            MaterialButton b = new MaterialButton(c, null,
                    i == 0 ? com.google.android.material.R.attr.materialButtonStyle
                            : com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(options.get(i));
            b.setAllCaps(false);
            b.setOnClickListener(v -> onPick.picked(index));
            box.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return box;
    }

    private static WindowManager.LayoutParams params(Context c) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        int margin = dp(c, 16);
        lp.horizontalMargin = 0;
        lp.y = dp(c, 48);
        lp.width = c.getResources().getDisplayMetrics().widthPixels - 2 * margin;
        return lp;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
