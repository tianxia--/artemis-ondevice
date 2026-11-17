package com.artemis.ondevice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

/**
 * The voice call on screen: an orb that breathes with the voice that is speaking, what the call
 * is doing, live captions of both sides, and a hang-up button. Drawn by the accessibility service
 * at the top of the screen (no overlay permission). While the agent works on a task it shrinks to
 * a small pill ("voice call · running the task", the dot still moves with the user's voice) that
 * ignores touches and is hidden while a screenshot is taken. Any thread may call it.
 */
final class VoiceCallPanel {

    enum State { CONNECTING, LISTENING, HEARING, THINKING, SPEAKING, TASK }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AgentAccessibilityService service;
    private final WindowManager wm;
    private final Runnable onHangUp;
    private View card, pill;
    private Orb orb, pillOrb;
    private TextView status, you, assistant;
    private boolean visible = true;
    /** The panel on screen, for hiding it from screenshots ({@link #hideForCapture}). */
    private static volatile VoiceCallPanel active;

    private VoiceCallPanel(AgentAccessibilityService service, Runnable onHangUp) {
        this.service = service;
        this.wm = service.getSystemService(WindowManager.class);
        this.onHangUp = onHangUp;
    }

    /** Shows the panel; null when the accessibility service is off (the call still works). */
    static VoiceCallPanel show(Runnable onHangUp) {
        AgentAccessibilityService s = AgentAccessibilityService.get();
        if (s == null) return null;
        VoiceCallPanel p = new VoiceCallPanel(s, onHangUp);
        p.main.post(p::attach);
        active = p;
        return p;
    }

    void state(State state) {
        main.post(() -> {
            if (card == null) return;
            orb.state = state;
            status.setText(AppLocale.wrap(service).getString(label(state)));
        });
    }

    /** Loudness of the voice speaking now, 0..1: the user's while listening, the reply's while speaking. */
    void level(float value) {
        main.post(() -> {
            float v = Math.max(0f, Math.min(1f, value));
            if (orb != null) orb.target = v;
            if (pillOrb != null) pillOrb.target = v;
        });
    }

    void userSaid(String text) {
        main.post(() -> {
            if (card == null) return;
            you.setText(text);
            you.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
            assistant.setText("");
            assistant.setVisibility(View.GONE);
        });
    }

    void assistantSays(String text) {
        main.post(() -> {
            if (card == null) return;
            assistant.setText(text);
            assistant.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        });
    }

    /** The full panel, or (false) the small pill while the agent works; full again for its questions and the result. */
    void setVisible(boolean value) {
        main.post(() -> {
            if (card == null || visible == value) return;
            visible = value;
            card.setVisibility(value ? View.VISIBLE : View.GONE);
            pill.setVisibility(value ? View.GONE : View.VISIBLE);
        });
    }

    void close() {
        if (active == this) active = null;
        main.post(() -> {
            if (card != null && card.isAttachedToWindow()) wm.removeView(card);
            if (pill != null && pill.isAttachedToWindow()) wm.removeView(pill);
            card = null;
            pill = null;
            orb = null;
            pillOrb = null;
        });
    }

    /** Before a screenshot (loop thread): make the panel invisible and wait for the frame. */
    static void hideForCapture() {
        VoiceCallPanel p = active;
        if (p != null) p.setAlpha(0f, true);
    }

    static void showAfterCapture() {
        VoiceCallPanel p = active;
        if (p != null) p.setAlpha(1f, false);
    }

    private void setAlpha(float alpha, boolean wait) {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        main.post(() -> {
            if (card != null) card.setAlpha(alpha);
            if (pill != null) {
                pill.setAlpha(alpha);
                pill.postOnAnimation(done::countDown);
            } else {
                done.countDown();
            }
        });
        if (!wait) return;
        try {
            done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            Thread.sleep(34); // one more frame for the compositor
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int label(State s) {
        switch (s) {
            case CONNECTING: return R.string.voice_call_connecting;
            case HEARING: return R.string.voice_call_hearing;
            case THINKING: return R.string.voice_call_thinking;
            case SPEAKING: return R.string.voice_call_speaking;
            case TASK: return R.string.voice_call_task;
            default: return R.string.voice_call_listening;
        }
    }

    // ------------------------------------------------------------------ views (main thread)

    private void attach() {
        Context c = new ContextThemeWrapper(AppLocale.wrap(service), R.style.Theme_Artemis);
        int pad = dp(c, 16);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, dp(c, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.getColor(R.color.surface_card));
        bg.setCornerRadius(dp(c, 24));
        bg.setStroke(dp(c, 2), c.getColor(R.color.brand));
        box.setBackground(bg);
        box.setElevation(dp(c, 12));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        orb = new Orb(c);
        row.addView(orb, new LinearLayout.LayoutParams(dp(c, 64), dp(c, 64)));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(c, 12), 0, 0, 0);
        status = text(c, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.brand, 1);
        status.setText(R.string.voice_call_connecting);
        you = text(c, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, R.color.text_secondary, 2);
        assistant = text(c, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, R.color.text_primary, 3);
        texts.addView(status);
        texts.addView(you);
        texts.addView(assistant);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);

        MaterialButton hangUp = new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        hangUp.setText(R.string.voice_call_hang_up);
        hangUp.setTextColor(c.getColor(R.color.danger));
        hangUp.setAllCaps(false);
        hangUp.setOnClickListener(v -> onHangUp.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        box.addView(hangUp, lp);

        card = box;
        card.setVisibility(visible ? View.VISIBLE : View.GONE);
        wm.addView(card, params(c));

        LinearLayout small = new LinearLayout(c);
        small.setOrientation(LinearLayout.HORIZONTAL);
        small.setGravity(Gravity.CENTER_VERTICAL);
        small.setPadding(dp(c, 10), dp(c, 6), dp(c, 14), dp(c, 6));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(c.getColor(R.color.surface_card));
        pillBg.setCornerRadius(dp(c, 20));
        pillBg.setStroke(dp(c, 1), c.getColor(R.color.brand));
        small.setBackground(pillBg);
        small.setElevation(dp(c, 6));
        pillOrb = new Orb(c);
        pillOrb.state = State.LISTENING;
        small.addView(pillOrb, new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));
        TextView label = text(c, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, R.color.brand, 1);
        label.setText(R.string.voice_call_task);
        label.setPadding(dp(c, 8), 0, 0, 0);
        small.addView(label);
        pill = small;
        pill.setVisibility(visible ? View.GONE : View.VISIBLE);
        wm.addView(pill, pillParams(c));
    }

    /** Top centre, never touchable: the agent's taps there reach the app below. */
    private static WindowManager.LayoutParams pillParams(Context c) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(c, 32);
        return lp;
    }

    private static TextView text(Context c, int appearance, int color, int maxLines) {
        TextView t = new TextView(c);
        t.setTextAppearance(appearance);
        t.setTextColor(c.getColor(color));
        t.setMaxLines(maxLines);
        t.setEllipsize(TextUtils.TruncateAt.START); // captions grow at the end: keep the latest words
        if (maxLines > 1) t.setVisibility(View.GONE);
        return t;
    }

    private static WindowManager.LayoutParams params(Context c) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.y = dp(c, 40);
        lp.width = c.getResources().getDisplayMetrics().widthPixels - 2 * dp(c, 12);
        return lp;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /**
     * A circle that swells with the voice level and breathes slowly when nobody speaks; a ring
     * spins while connecting, thinking or running the task.
     */
    private static final class Orb extends View {
        State state = State.CONNECTING;
        float target, level;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int brand, success;

        Orb(Context c) {
            super(c);
            brand = c.getColor(R.color.brand);
            success = c.getColor(R.color.success);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(c.getResources().getDisplayMetrics().density * 3);
            ring.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            level += (target - level) * 0.25f; // smooth
            target *= 0.9f;                    // fall back when no new level arrives
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, max = Math.min(w, h) / 2f;
            double t = SystemClock.uptimeMillis() / 1000.0;
            float breathe = (float) (0.5 + 0.5 * Math.sin(t * 2.2));
            int color = state == State.SPEAKING ? success : brand;
            boolean busy = state == State.CONNECTING || state == State.THINKING || state == State.TASK;

            float core = max * (0.46f + 0.05f * breathe + 0.30f * level);
            halo.setColor(color);
            halo.setAlpha((int) (40 + 60 * level));
            canvas.drawCircle(cx, cy, Math.min(max, core * (1.25f + 0.25f * level)), halo);
            fill.setColor(color);
            fill.setAlpha(busy ? 150 : 255);
            canvas.drawCircle(cx, cy, core, fill);
            if (busy) {
                ring.setColor(color);
                float r = max - ring.getStrokeWidth();
                float start = (float) ((t * 300) % 360);
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, start, 100, false, ring);
            }
            postInvalidateOnAnimation();
        }
    }
}
