package com.artemis.ondevice;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The phone actions behind the agent's tools (tap, long_press, type_text, swipe, back, home,
 * open_app, wait), shared by the model loop, skill replay and the MCP server so they all act
 * the same way. Targets are element indices of the given observation or 0-1000 coordinates.
 */
final class Actions {

    private static final long SETTLE_MS = 500L;
    private static final String CLIPBOARD_HANDOFF =
            "The text is now on the clipboard: long-press the input field, tap Paste (粘贴), and check the field before sending.";
    /** Longest wait for a launched app to come to the front and stop changing. */
    private static final long LAUNCH_SETTLE_MAX_MS = 5000;

    private Actions() {}

    /** Executes one tool call; returns a short ledger entry ("tap(index=5 'Battery') ok"). */
    static String execute(AgentAccessibilityService s, Observation obs, ChatClient.ToolCall call)
            throws InterruptedException {
        JSONObject a = call.args;
        try {
            switch (call.name) {
                case "tap": {
                    float[] p = target(obs, a);
                    clear(s, p[0], p[1]);
                    boolean ok = Gestures.tap(s, p[0], p[1], 3000);
                    settle(SETTLE_MS);
                    return "tap(" + describe(obs, a) + ") " + (ok ? "ok" : "FAILED: gesture cancelled");
                }
                case "long_press": {
                    float[] p = target(obs, a);
                    long ms = Math.max(500, Math.min(10_000, a.optLong("duration_ms", 1000)));
                    clear(s, p[0], p[1]);
                    boolean ok = Gestures.longPress(s, p[0], p[1], ms);
                    settle(SETTLE_MS);
                    return "long_press(" + describe(obs, a) + ", " + ms + "ms) " + (ok ? "ok" : "FAILED: gesture cancelled");
                }
                case "type_text": {
                    if (a.has("index")) {
                        float[] p = target(obs, a);
                        clear(s, p[0], p[1]);
                        Gestures.tap(s, p[0], p[1], 3000);
                        settle(400);
                    }
                    String text = a.optString("text");
                    boolean ok = Gestures.setText(s, text, false);
                    String head = "type_text('" + FlashLoop.clip(text, 40) + "'" + (a.has("index") ? ", index=" + a.optInt("index") : "") + ") ";
                    if (ok) {
                        settle(SETTLE_MS);
                        return head + "ok";
                    }
                    // Apps such as WeChat hide their input fields from accessibility services. Their
                    // own Paste menu still works, so hand the text over through the clipboard.
                    s.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("artemis", text));
                    return head + "FAILED: no editable field. " + CLIPBOARD_HANDOFF;
                }
                case "swipe": {
                    String dir = a.optString("direction", "up");
                    String distance = a.optString("distance", "medium");
                    float frac = "short".equals(distance) ? 0.15f : "long".equals(distance) ? 0.6f : 0.3f;
                    float cx = obs.screenW / 2f, cy = obs.screenH / 2f;
                    if (a.has("index")) {
                        float[] p = target(obs, a);
                        cx = p[0];
                        cy = p[1];
                    }
                    float dx = 0, dy = 0;
                    switch (dir) {
                        case "up": dy = -obs.screenH * frac; break;
                        case "down": dy = obs.screenH * frac; break;
                        case "left": dx = -obs.screenW * frac; break;
                        default: dx = obs.screenW * frac; break;
                    }
                    float x1 = clampX(obs, cx - dx / 2), y1 = clampY(obs, cy - dy / 2);
                    float x2 = clampX(obs, cx + dx / 2), y2 = clampY(obs, cy + dy / 2);
                    clear(s, x1, y1);
                    clear(s, (x1 + x2) / 2, (y1 + y2) / 2);
                    clear(s, x2, y2);
                    boolean ok = Gestures.swipe(s, x1, y1, x2, y2, 300, 3000);
                    settle(SETTLE_MS);
                    return "swipe(" + dir + ", " + distance + ") " + (ok ? "ok" : "FAILED");
                }
                case "back":
                case "home": {
                    boolean ok = Gestures.performGlobalAction(s, call.name);
                    settle(SETTLE_MS);
                    return call.name + "() " + (ok ? "ok" : "FAILED");
                }
                case "open_app": {
                    String app = a.optString("app");
                    if (FlashLoop.BLOCKED_APPS.contains(app.trim())) {
                        return "open_app('" + app + "') FAILED: this app is blocked (account ban risk); call finish(status=\"failed\")";
                    }
                    String before = s.currentPackage();
                    String pkg = launch(s, app);
                    if (pkg == null) {
                        return "open_app('" + app + "') FAILED: no launchable app matches; use the label shown on the home screen";
                    }
                    long ms = settleOnApp(s, obs, pkg);
                    String front = s.currentPackage();
                    if (!pkg.equals(front) && (front.equals(before) || front.equals(homePackage(s)))) {
                        // Some phones (seen on Huawei) drop the first launch from the background without
                        // any dialog: the home screen or the previous app is still in front. Launching
                        // again works. A dialog in front is left to the model.
                        launch(s, pkg);
                        ms += settleOnApp(s, obs, pkg);
                        front = s.currentPackage();
                    }
                    if (!pkg.equals(front)) {
                        // Some phones ask before one app opens another; the launch waits behind that dialog.
                        return "open_app('" + app + "') -> " + pkg + " started, but " + (front.isEmpty() ? "another app" : front)
                                + " is still in front after " + ms + "ms: a dialog may be asking to allow the launch; "
                                + "observe and handle it, do not launch again yet";
                    }
                    return "open_app('" + app + "') ok -> " + pkg + " (ready after " + ms + "ms)";
                }
                case "wait": {
                    long ms = Math.max(200, Math.min(10_000, a.optLong("ms", 1000)));
                    settle(ms);
                    return "wait(" + ms + "ms) ok";
                }
                default:
                    return call.name + " FAILED: unknown tool";
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return call.name + " FAILED: " + e.getMessage();
        }
    }

    /** Makes sure the floating bubble / panel is not where the gesture lands. */
    private static void clear(AgentAccessibilityService s, float x, float y) {
        FloatingBubble b = s.bubble();
        if (b != null) b.clearPoint(x, y);
    }

    /** Screen pixel of the target: element index first, normalized x,y otherwise. */
    private static float[] target(Observation obs, JSONObject a) {
        if (a.has("index") && !a.isNull("index")) {
            int i = a.optInt("index", -1);
            if (i < 0 || i >= obs.elements.size()) {
                throw new IllegalArgumentException("index " + i + " is not in the current element list");
            }
            Observation.Element e = obs.elements.get(i);
            return new float[]{e.cx(), e.cy()};
        }
        if (a.has("x") && a.has("y")) {
            int x = a.optInt("x", -1), y = a.optInt("y", -1);
            if (x < 0 || x > 1000 || y < 0 || y > 1000) {
                throw new IllegalArgumentException("x,y (" + x + "," + y + ") is outside the 0-1000 scale");
            }
            // (0,0) is the corner of the status bar, never a real target: the model left x,y unset.
            if (x <= 5 && y <= 5) {
                throw new IllegalArgumentException("x,y (0,0) is not a target; pass the element index or real coordinates");
            }
            return new float[]{
                    clampX(obs, a.optInt("x") / 1000f * obs.screenW),
                    clampY(obs, a.optInt("y") / 1000f * obs.screenH)};
        }
        throw new IllegalArgumentException("needs index or x,y");
    }

    private static String describe(Observation obs, JSONObject a) {
        if (a.has("index") && !a.isNull("index")) {
            int i = a.optInt("index", -1);
            String label = i >= 0 && i < obs.elements.size() ? obs.elements.get(i).label : "?";
            return "index=" + i + " " + FlashLoop.clip(label, 40);
        }
        return "x=" + a.optInt("x") + ",y=" + a.optInt("y") + " '" + FlashLoop.clip(a.optString("target_description"), 30) + "' (self-described)";
    }

    private static float clampX(Observation o, float v) { return Math.max(1, Math.min(o.screenW - 2, v)); }
    private static float clampY(Observation o, float v) { return Math.max(1, Math.min(o.screenH - 2, v)); }

    private static String launch(AgentAccessibilityService s, String app) {
        PackageManager pm = s.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(app.trim());
        String pkg = app.trim();
        if (intent == null) {
            pkg = findApp(pm, app);
            if (pkg == null) return null;
            if (FlashLoop.BLOCKED_APPS.contains(pkg)) throw new IllegalArgumentException(
                    "this app is blocked (account ban risk); call finish(status=\"failed\")");
            intent = pm.getLaunchIntentForPackage(pkg);
            if (intent == null) return null;
        }
        // Start fresh (like Artemis manage_app launch), not on whatever page the app was left on.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        s.startActivity(intent);
        return pkg;
    }

    /**
     * English names the model tends to use for apps whose launcher label is Chinese on the
     * phone (and the other way round). Each entry lists names of the same app.
     */
    private static final String[][] APP_ALIASES = {
            {"settings", "设置"}, {"camera", "相机"}, {"gallery", "photos", "图库", "相册"},
            {"clock", "alarm", "时钟", "闹钟"}, {"calendar", "日历"}, {"calculator", "计算器"},
            {"contacts", "联系人", "通讯录"}, {"phone", "dialer", "电话", "拨号"},
            {"messages", "messaging", "sms", "信息", "短信"}, {"browser", "浏览器"},
            {"files", "file manager", "文件管理", "文件"}, {"notes", "memo", "备忘录", "笔记"},
            {"weather", "天气"}, {"music", "音乐"}, {"app store", "appgallery", "play store", "应用市场"},
            {"maps", "地图"}, {"email", "mail", "电子邮件", "邮件"}, {"recorder", "录音机"},
    };

    /**
     * Package of the launchable app that best matches a label the model gave: exact label,
     * then label starting with / containing the name, then the name containing the label
     * ("打开设置应用" -> 设置), then the package name. Case, spaces and punctuation are ignored,
     * and common English/Chinese names are tried as aliases. Null when nothing matches.
     */
    private static String findApp(PackageManager pm, String app) {
        List<String> wants = new ArrayList<>();
        String want = norm(app);
        if (want.isEmpty()) return null;
        wants.add(want);
        for (String[] group : APP_ALIASES) {
            for (String name : group) {
                if (!norm(name).equals(want)) continue;
                for (String alias : group) if (!wants.contains(norm(alias))) wants.add(norm(alias));
            }
        }
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String best = null;
        int bestScore = 0;
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String label = norm(String.valueOf(ri.loadLabel(pm)));
            String pkg = ri.activityInfo.packageName;
            for (int i = 0; i < wants.size(); i++) {
                String w = wants.get(i);
                int score;
                if (label.equals(w)) score = 50;
                else if (label.startsWith(w)) score = 40;
                else if (label.contains(w)) score = 30;
                else if (label.length() >= 2 && w.contains(label)) score = 20 + Math.min(label.length(), 9);
                else if (w.length() >= 3 && pkg.toLowerCase(Locale.ROOT).contains(w)) score = 10;
                else continue;
                if (i > 0) score -= 1; // the name as given beats an alias
                if (score > bestScore) {
                    bestScore = score;
                    best = pkg;
                }
            }
        }
        return best;
    }

    /** Package of the home screen (launcher), or "". */
    private static String homePackage(AgentAccessibilityService s) {
        ResolveInfo ri = s.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null || ri.activityInfo == null ? "" : ri.activityInfo.packageName;
    }

    /**
     * Launchable apps whose name appears in the task, as "label = package" (at most 5): the model
     * can open them with open_app right away instead of looking for their icons.
     */
    static List<String> appsNamedIn(PackageManager pm, String task, String self) {
        List<String> out = new ArrayList<>();
        String t = norm(task);
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            String label = String.valueOf(ri.loadLabel(pm));
            String n = norm(label);
            if (n.length() < 2 || pkg.equals(self) || FlashLoop.BLOCKED_APPS.contains(pkg) || !t.contains(n)) continue;
            String entry = label + " = " + pkg;
            if (!out.contains(entry)) out.add(entry);
            if (out.size() == 5) break;
        }
        return out;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·•（）()]+", "");
    }

    /**
     * After a launch, waits until the app is in front and its element list stopped changing
     * (two polls in a row unchanged), at most {@link #LAUNCH_SETTLE_MAX_MS}. Returns the wait.
     * A splash screen that holds still can end the wait early; the model can still call wait.
     */
    private static long settleOnApp(AgentAccessibilityService s, Observation obs, String pkg)
            throws InterruptedException {
        long t0 = System.currentTimeMillis();
        android.graphics.Rect screen = new android.graphics.Rect(0, 0, obs.screenW, obs.screenH);
        String prev = null;
        int same = 0;
        Thread.sleep(300);
        while (System.currentTimeMillis() - t0 < LAUNCH_SETTLE_MAX_MS) {
            if (pkg.equals(s.currentPackage())) {
                StringBuilder sig = new StringBuilder();
                for (Observation.Element e : UiTree.collect(s, screen, 400).elements) sig.append(e.label).append('\n');
                String now = sig.toString();
                same = !now.isEmpty() && now.equals(prev) ? same + 1 : 0;
                if (same >= 2) break;
                prev = now;
            }
            Thread.sleep(250);
        }
        return System.currentTimeMillis() - t0;
    }

    private static void settle(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
