package com.artemis.ondevice;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * The agent's accessibility service: gestures, screenshots and the UI tree for the
 * on-device loop, and the host of the floating bubble. It opens no socket; it only
 * exposes itself to the rest of this process.
 */
public class AgentAccessibilityService extends AccessibilityService {

    private static volatile AgentAccessibilityService instance;
    private volatile String currentPackage = "";
    private volatile FloatingBubble bubble;
    private final Handler main = new Handler(Looper.getMainLooper());

    static AgentAccessibilityService get() {
        return instance;
    }

    String currentPackage() {
        return currentPackage;
    }

    /** The bubble when shown, else null. */
    FloatingBubble bubble() {
        return bubble;
    }

    /** Shows or removes the bubble to match the setting. Main thread. */
    void applyBubbleSetting() {
        boolean want = AppSettings.bubble(this);
        if (want && bubble == null) {
            FloatingBubble b = new FloatingBubble(this);
            b.show();
            bubble = b;
        } else if (!want && bubble != null) {
            bubble.remove();
            bubble = null;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(AgentService.TAG, "accessibility service connected");
        main.post(new Runnable() {
            @Override public void run() { applyBubbleSetting(); }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) {
            return;
        }
        String pkg = event.getPackageName().toString();
        boolean ours = getPackageName().equals(pkg);
        // The bubble's own windows report our package too, so for our package ask which
        // application window is actually active instead of trusting the event.
        boolean ourScreen = ours && ourAppWindowActive();
        if (ours && !ourScreen) return;
        currentPackage = pkg;
        // While our own screens are in front the run holds: the agent must not act on them, and
        // the user is looking at (or stopping) the run. The bubble would only cover them.
        RunState.setHold(RunState.Hold.APP_SCREEN, ourScreen);
        if (bubble != null) bubble.setSuppressed(ourScreen);
    }

    private boolean ourAppWindowActive() {
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive()) continue;
            AccessibilityNodeInfo root = w.getRoot();
            return root != null && getPackageName().contentEquals(root.getPackageName());
        }
        return false;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (bubble != null) {
            bubble.remove();
            bubble = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
