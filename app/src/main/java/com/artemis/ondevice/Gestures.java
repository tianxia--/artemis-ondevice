package com.artemis.ondevice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synchronous gestures and actions through the accessibility service (no root, no adb). */
final class Gestures {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Gestures() {}

    static boolean tap(AccessibilityService s, float x, float y, long timeoutMs) {
        Path path = new Path();
        path.moveTo(x, y);
        return dispatch(s, new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 60L)).build(), timeoutMs);
    }

    static boolean swipe(AccessibilityService s, float x1, float y1, float x2, float y2,
                         long durationMs, long timeoutMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        long d = Math.max(50L, Math.min(durationMs, 5000L));
        return dispatch(s, new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, d)).build(), timeoutMs + d);
    }

    /** ACTION_SET_TEXT on the input-focused field, else the first visible editable field. */
    static boolean setText(AccessibilityService s, String text, boolean append) {
        AccessibilityNodeInfo node = findInputNode(s);
        if (node == null) return false;
        String value = text == null ? "" : text;
        if (append && node.getText() != null && !node.isShowingHintText()) {
            value = node.getText() + value;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    static boolean performGlobalAction(AccessibilityService s, String name) {
        switch (name) {
            case "back": return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            case "home": return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            default: return false;
        }
    }

    private static AccessibilityNodeInfo findInputNode(AccessibilityService s) {
        for (AccessibilityWindowInfo w : s.getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && focused.isEditable()) return focused;
        }
        for (AccessibilityWindowInfo w : s.getWindows()) {
            AccessibilityNodeInfo found = firstEditable(w.getRoot(), 0);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > UiTree.MAX_DEPTH) return null;
        if (node.isEditable() && node.isEnabled() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = firstEditable(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean dispatch(final AccessibilityService s, final GestureDescription g, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        MAIN.post(new Runnable() {
            @Override public void run() {
                boolean accepted = s.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription d) { ok.set(true); latch.countDown(); }
                    @Override public void onCancelled(GestureDescription d) { latch.countDown(); }
                }, null);
                if (!accepted) latch.countDown();
            }
        });
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && ok.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
