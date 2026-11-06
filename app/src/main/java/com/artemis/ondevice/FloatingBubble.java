package com.artemis.ondevice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Floating entry point drawn by the accessibility service (TYPE_ACCESSIBILITY_OVERLAY, so no
 * "draw over other apps" permission). A draggable bubble shows the live run (step, phase);
 * tapping it opens a small panel: Stop / details while running, the last result and
 * New task / Run again when idle.
 *
 * It never gets in the agent's way: it is hidden while a screenshot is taken and made
 * touch-transparent while gestures are dispatched. All view work happens on the main thread.
 */
final class FloatingBubble implements RunState.Listener {

    private static final long TICK_MS = 1000L;

    private final AgentAccessibilityService service;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View bubble;
    private final View panel;
    private final WindowManager.LayoutParams bubbleLp;
    private final WindowManager.LayoutParams panelLp;
    private boolean panelShown;
    private boolean suppressed;
    private volatile boolean attached;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            if (RunState.isRunning()) main.postDelayed(this, TICK_MS);
        }
    };

    FloatingBubble(AgentAccessibilityService service) {
        this.service = service;
        this.wm = service.getSystemService(WindowManager.class);
        Context themed = new android.view.ContextThemeWrapper(service, R.style.Theme_Artemis);
        LayoutInflater inflater = LayoutInflater.from(themed);
        bubble = inflater.inflate(R.layout.overlay_bubble, null);
        panel = inflater.inflate(R.layout.overlay_panel, null);

        bubbleLp = overlayParams();
        // An inflated root without a parent loses its layout size; give the window the bubble's size.
        bubbleLp.width = dp(60);
        bubbleLp.height = dp(60);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        Rect screen = wm.getMaximumWindowMetrics().getBounds();
        bubbleLp.x = screen.width() - dp(68);
        bubbleLp.y = screen.height() / 3;

        panelLp = overlayParams();
        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelLp.width = Math.min(screen.width() - dp(32), dp(360));
        panelLp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;

        wireBubble();
        wirePanel();
    }

    private static WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        return lp;
    }

    // ------------------------------------------------------------------ lifecycle (main thread)

    void show() {
        if (attached) return;
        wm.addView(bubble, bubbleLp);
        attached = true;
        RunState.subscribe(this);
    }

    void remove() {
        if (!attached) return;
        RunState.unsubscribe(this);
        main.removeCallbacks(tick);
        hidePanel();
        wm.removeView(bubble);
        attached = false;
    }

    @Override
    public void onRunChanged() {
        render();
        main.removeCallbacks(tick);
        if (RunState.isRunning()) main.postDelayed(tick, TICK_MS);
    }

    /** Hidden while one of our own screens is in front. Main thread (accessibility events). */
    void setSuppressed(boolean value) {
        if (suppressed == value || !attached) return;
        suppressed = value;
        if (value) hidePanel();
        bubble.setVisibility(value ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ loop thread hooks

    /**
     * Makes bubble and panel fully transparent and waits a frame so the next screenshot does not
     * include them. Alpha, not visibility: the bubble stays tappable, so a tap meant for it never
     * falls through to the app being automated.
     */
    void hideForCapture() {
        if (!attached) return;
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override public void run() {
                bubble.setAlpha(0f);
                panel.setAlpha(0f);
                bubble.postOnAnimation(new Runnable() {
                    @Override public void run() { done.countDown(); }
                });
            }
        });
        try {
            done.await(200, TimeUnit.MILLISECONDS);
            Thread.sleep(34); // one more frame for the compositor
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void showAfterCapture() {
        main.post(new Runnable() {
            @Override public void run() {
                bubble.setAlpha(1f);
                panel.setAlpha(1f);
            }
        });
    }

    /**
     * Before a gesture at screen point (x, y): closes the panel if the point is under it, and
     * moves the bubble out of the way if the point is under it, so the gesture reaches the app.
     * Called from the loop thread; waits until the windows have moved.
     */
    void clearPoint(final float x, final float y) {
        if (!attached) return;
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override public void run() {
                boolean changed = false;
                int pad = dp(12);
                if (panelShown) {
                    int[] at = new int[2];
                    panel.getLocationOnScreen(at);
                    if (x >= at[0] - pad && x <= at[0] + panel.getWidth() + pad
                            && y >= at[1] - pad && y <= at[1] + panel.getHeight() + pad) {
                        hidePanel();
                        changed = true;
                    }
                }
                if (bubble.getVisibility() == View.VISIBLE
                        && x >= bubbleLp.x - pad && x <= bubbleLp.x + bubbleLp.width + pad
                        && y >= bubbleLp.y - pad && y <= bubbleLp.y + bubbleLp.height + pad) {
                    Rect screen = wm.getMaximumWindowMetrics().getBounds();
                    bubbleLp.y = y < screen.height() / 2f ? screen.height() * 3 / 4 : screen.height() / 4;
                    wm.updateViewLayout(bubble, bubbleLp);
                    changed = true;
                }
                if (changed) {
                    bubble.postOnAnimation(new Runnable() {
                        @Override public void run() { done.countDown(); }
                    });
                } else {
                    done.countDown();
                }
            }
        });
        try {
            done.await(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ bubble

    @SuppressLint("ClickableViewAccessibility")
    private void wireBubble() {
        final int slop = dp(6);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                        if (dragging) {
                            bubbleLp.x = startX + (int) dx;
                            bubbleLp.y = startY + (int) dy;
                            wm.updateViewLayout(bubble, bubbleLp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) snapToEdge();
                        else togglePanel();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void snapToEdge() {
        Rect screen = wm.getMaximumWindowMetrics().getBounds();
        int w = bubble.getWidth();
        bubbleLp.x = bubbleLp.x + w / 2 < screen.width() / 2 ? dp(4) : screen.width() - w - dp(4);
        bubbleLp.y = Math.max(dp(48), Math.min(screen.height() - bubble.getHeight() - dp(48), bubbleLp.y));
        wm.updateViewLayout(bubble, bubbleLp);
    }

    private void render() {
        RunRecord r = RunState.current();
        boolean running = r != null && r.isRunning();
        ProgressBar ring = bubble.findViewById(R.id.bubble_ring);
        TextView step = bubble.findViewById(R.id.bubble_step);
        ImageView icon = bubble.findViewById(R.id.bubble_icon);
        View core = bubble.findViewById(R.id.bubble_core);

        ring.setVisibility(running ? View.VISIBLE : View.GONE);
        step.setVisibility(running ? View.VISIBLE : View.GONE);
        icon.setVisibility(running ? View.GONE : View.VISIBLE);
        int color = r == null || running ? service.getColor(R.color.brand) : Format.statusColor(service, r.status);
        ((GradientDrawable) core.getBackground().mutate()).setColor(color);
        if (running) {
            step.setText(String.valueOf(r.steps.size() + 1));
        } else if (r != null && !r.isRunning()) {
            icon.setImageResource(RunRecord.COMPLETED.equals(r.status) ? R.drawable.ic_check
                    : RunRecord.STOPPED.equals(r.status) ? R.drawable.ic_stop : R.drawable.ic_error);
        } else {
            icon.setImageResource(R.drawable.ic_spark);
        }
        if (panelShown) renderPanel(r);
    }

    // ------------------------------------------------------------------ panel

    @SuppressLint("ClickableViewAccessibility")
    private void wirePanel() {
        panel.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                // Tapping outside closes the panel, but not during a run: the agent's own gestures
                // also arrive as outside touches (gestures under the panel close it via clearPoint).
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE && !RunState.isRunning()) {
                    hidePanel();
                    return true;
                }
                return false;
            }
        });
        panel.findViewById(R.id.panel_close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hidePanel(); }
        });
        panel.findViewById(R.id.panel_stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { AgentService.stop(service); }
        });
        panel.findViewById(R.id.panel_details).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                RunRecord r = RunState.current();
                if (r != null) {
                    hidePanel();
                    service.startActivity(new Intent(service, RunDetailActivity.class)
                            .putExtra(RunDetailActivity.EXTRA_RUN_ID, r.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                }
            }
        });
        panel.findViewById(R.id.panel_new).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hidePanel();
                service.startActivity(new Intent(service, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_FOCUS_TASK, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
        });
        panel.findViewById(R.id.panel_rerun).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                RunRecord r = RunState.current();
                if (r != null && !RunState.isRunning()) {
                    hidePanel();
                    AgentService.start(service, r.task);
                }
            }
        });
    }

    private void togglePanel() {
        if (panelShown) hidePanel();
        else showPanel();
    }

    private void showPanel() {
        Rect screen = wm.getMaximumWindowMetrics().getBounds();
        int bubbleBottom = bubbleLp.y + bubble.getHeight();
        panelLp.y = bubbleBottom + dp(8) + dp(240) < screen.height()
                ? bubbleBottom + dp(8) : Math.max(dp(48), bubbleLp.y - dp(248));
        renderPanel(RunState.current());
        panel.setVisibility(View.VISIBLE);
        wm.addView(panel, panelLp);
        panelShown = true;
        RunState.setHold(RunState.Hold.PANEL, true);
    }

    private void hidePanel() {
        if (!panelShown) return;
        wm.removeView(panel);
        panelShown = false;
        RunState.setHold(RunState.Hold.PANEL, false);
    }

    private void renderPanel(RunRecord r) {
        TextView title = panel.findViewById(R.id.panel_title);
        TextView status = panel.findViewById(R.id.panel_status);
        TextView body = panel.findViewById(R.id.panel_body);
        TextView meta = panel.findViewById(R.id.panel_meta);
        boolean running = r != null && r.isRunning();
        panel.findViewById(R.id.panel_stop).setVisibility(running ? View.VISIBLE : View.GONE);
        panel.findViewById(R.id.panel_details).setVisibility(r != null ? View.VISIBLE : View.GONE);
        panel.findViewById(R.id.panel_new).setVisibility(running ? View.GONE : View.VISIBLE);
        panel.findViewById(R.id.panel_rerun).setVisibility(!running && r != null ? View.VISIBLE : View.GONE);

        if (r == null) {
            title.setText(R.string.app_name);
            status.setText(R.string.phase_idle);
            status.setTextColor(service.getColor(R.color.neutral));
            body.setText(R.string.bubble_idle_hint);
            meta.setText("");
            return;
        }
        title.setText(r.task);
        status.setTextColor(Format.statusColor(service, r.status));
        if (running) {
            status.setText(RunState.paused() ? service.getString(R.string.status_paused, r.steps.size() + 1)
                    : service.getString(R.string.status_progress, r.steps.size() + 1,
                            Format.phase(service, RunState.phase())));
            RunRecord.Step last = r.lastStep();
            body.setText(last == null ? service.getString(R.string.bubble_first_step)
                    : last.actions.isEmpty() ? FlashLoop.clip(last.reasoning, 160)
                    : android.text.TextUtils.join("\n", last.actions));
        } else {
            status.setText(Format.status(service, r.status));
            body.setText(r.answer.isEmpty() ? service.getString(R.string.no_answer) : r.answer);
        }
        meta.setText(service.getString(R.string.bubble_meta, Format.duration(r.durationMs()), r.steps.size(),
                Format.tokens(r.promptTokens() + r.completionTokens())));
    }

    private int dp(int v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }
}
