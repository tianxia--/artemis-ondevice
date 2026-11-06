package com.artemis.ondevice;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Flat list of informative / interactive on-screen nodes across all interactive windows,
 * labelled the way Artemis's element list reads ("Text: 'Wi-Fi' | clickable").
 *
 * Android keeps reporting a page that another page sits on (e.g. a chat screen opened over
 * the conversation list in the same activity) as visible-to-user, and the tree cannot tell
 * which container is opaque, so nothing is dropped as "covered". When the list is over the
 * budget, the earliest elements are dropped instead: later nodes are drawn later, so the
 * controls on top (e.g. a chat input bar, usually last) are the ones that stay.
 */
final class UiTree {

    static final int MAX_DEPTH = 60;

    static final class Result {
        final List<Observation.Element> elements;
        /** Elements dropped from the front of the list to stay within the budget. */
        final int dropped;
        Result(List<Observation.Element> elements, int dropped) {
            this.elements = elements;
            this.dropped = dropped;
        }
    }

    private UiTree() {}

    static Result collect(AccessibilityService s, Rect screen, int max) {
        List<Observation.Element> all = new ArrayList<>();
        for (AccessibilityWindowInfo w : s.getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null) all.addAll(walk(root, 0, screen));
        }
        int dropped = Math.max(0, all.size() - max);
        return new Result(dropped > 0 ? new ArrayList<>(all.subList(dropped, all.size())) : all, dropped);
    }

    private static List<Observation.Element> walk(AccessibilityNodeInfo n, int depth, Rect screen) {
        List<Observation.Element> mine = new ArrayList<>();
        if (n == null || depth > MAX_DEPTH || !n.isVisibleToUser()) return mine;

        List<List<Observation.Element>> kids = new ArrayList<>();
        for (int i = 0; i < n.getChildCount(); i++) kids.add(walk(n.getChild(i), depth + 1, screen));
        boolean childLabelled = false;
        for (List<Observation.Element> k : kids) childLabelled |= !k.isEmpty();

        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.intersect(screen) && r.width() > 1 && r.height() > 1) {
            String label = label(n, childLabelled);
            if (label != null) mine.add(new Observation.Element(r.left, r.top, r.right, r.bottom, label));
        }
        for (List<Observation.Element> k : kids) mine.addAll(k);
        return mine;
    }

    /**
     * Label for the element list, or null when the node is neither informative nor interactive,
     * or is a bare container (no text, description, id or hint) whose children already describe it.
     */
    private static String label(AccessibilityNodeInfo n, boolean childLabelled) {
        String text = clean(n.getText());
        String desc = clean(n.getContentDescription());
        String state = clean(n.getStateDescription());
        String hint = n.isEditable() ? clean(n.getHintText()) : "";
        String id = n.getViewIdResourceName();
        boolean clickable = n.isClickable();
        boolean longClickable = n.isLongClickable();
        boolean editable = n.isEditable();
        boolean scrollable = n.isScrollable();
        boolean checkable = n.isCheckable();
        if (text.isEmpty() && desc.isEmpty() && !clickable && !longClickable && !editable && !scrollable && !checkable) {
            return null;
        }
        if (text.isEmpty() && desc.isEmpty() && hint.isEmpty() && id == null && !editable && childLabelled) {
            return null;
        }

        StringBuilder s = new StringBuilder();
        if (!text.isEmpty()) s.append("Text: '").append(text).append("'");
        if (!desc.isEmpty()) s.append(s.length() > 0 ? " | " : "").append("Desc: '").append(desc).append("'");
        if (text.isEmpty() && !hint.isEmpty()) s.append(s.length() > 0 ? " | " : "").append("Hint: '").append(hint).append("'");
        if (s.length() == 0) {
            String cls = n.getClassName() == null ? "View" : n.getClassName().toString();
            s.append(id != null ? "id: " + id.substring(id.indexOf('/') + 1) : cls.substring(cls.lastIndexOf('.') + 1));
        }
        List<String> flags = new ArrayList<>();
        if (clickable) flags.add("clickable");
        if (longClickable) flags.add("long-clickable");
        if (editable) flags.add("editable");
        if (scrollable) flags.add("scrollable");
        if (checkable) flags.add(n.isChecked() ? "checked" : "unchecked");
        if (!state.isEmpty()) flags.add("state=" + state);
        if (!flags.isEmpty()) s.append(" | ").append(TextUtils.join(",", flags));
        return s.toString();
    }

    private static String clean(CharSequence cs) {
        if (cs == null) return "";
        String s = cs.toString().replace('\n', ' ').trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
