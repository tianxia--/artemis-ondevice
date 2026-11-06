package com.artemis.ondevice;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * The agent's own accessibility service: gestures, screenshots and the UI tree for the
 * on-device loop. It opens no socket; it only exposes itself to
 * {@link AgentService} in this process.
 */
public class AgentAccessibilityService extends AccessibilityService {

    private static volatile AgentAccessibilityService instance;
    private volatile String currentPackage = "";

    static AgentAccessibilityService get() {
        return instance;
    }

    String currentPackage() {
        return currentPackage;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(AgentService.TAG, "accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            currentPackage = event.getPackageName().toString();
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
