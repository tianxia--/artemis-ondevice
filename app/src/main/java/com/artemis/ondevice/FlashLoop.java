package com.artemis.ondevice;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;


import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The agent loop, on the phone: observe (screenshot + indexed element list)
 * -> one model call with tools -> execute the tool calls -> repeat until {@code finish}
 * or the step cap. Element index first,
 * 0-1000 normalized coordinates as the fallback, and a turn-ending finish.
 */
final class FlashLoop {

    static final int HISTORY_STEPS = 6;
    static final int MAX_ACTIONS_PER_TURN = 3;
    static final int MAX_OUTPUT_TOKENS = 1024;
    private static final long SETTLE_MS = 500L;
    private static final String NO_TOOL_NUDGE =
            "You did not call any tools last turn. Please make progress by calling an action tool or 'finish'.";

    interface StopSignal {
        boolean stopped();
    }

    private final ChatClient client;
    private final String model;
    private final String goal;
    private final int maxSteps;
    private final StopSignal stop;
    private final boolean sendScreenshot;

    private final Deque<String> history = new ArrayDeque<>();
    private long startMs;

    // Totals
    private int steps;
    private int promptTokens;
    private int completionTokens;
    private final List<Long> llmMs = new ArrayList<>();
    private final List<Long> stepMs = new ArrayList<>();

    FlashLoop(ChatClient client, String model, String goal, int maxSteps,
              boolean sendScreenshot, StopSignal stop) {
        this.sendScreenshot = sendScreenshot;
        this.client = client;
        this.model = model;
        this.goal = goal;
        this.maxSteps = maxSteps;
        this.stop = stop;
    }

    void run() {
        startMs = System.currentTimeMillis();
        String outcome = "stopped";
        String answer = "";
        String nudge = null;
        try {
            for (int step = 1; step <= maxSteps && !stop.stopped(); step++) {
                AgentAccessibilityService a11y = AgentAccessibilityService.get();
                if (a11y == null) {
                    outcome = "failed";
                    answer = "Accessibility service is not enabled.";
                    break;
                }
                long t0 = System.currentTimeMillis();
                Observation obs = Observation.capture(a11y);
                long tObs = System.currentTimeMillis();

                String prompt = buildUserPrompt(obs, step, nudge);
                ChatClient.Result r = client.complete(model, systemPrompt(), prompt,
                        sendScreenshot ? obs.jpeg : null, tools(), MAX_OUTPUT_TOKENS);
                long tLlm = System.currentTimeMillis();
                if (stop.stopped()) break;
                promptTokens += r.promptTokens;
                completionTokens += r.completionTokens;
                llmMs.add(r.latencyMs);
                steps = step;

                String reasoning = r.text == null ? "" : r.text.trim();
                List<String> done = new ArrayList<>();
                boolean finished = false;
                nudge = r.toolCalls.isEmpty() ? NO_TOOL_NUDGE : null;
                int n = 0;
                for (ChatClient.ToolCall call : r.toolCalls) {
                    if (n++ >= MAX_ACTIONS_PER_TURN || stop.stopped()) break;
                    if ("finish".equals(call.name)) {
                        outcome = call.args.optString("status", "completed");
                        answer = call.args.optString("answer", "");
                        done.add("finish(" + outcome + ")");
                        finished = true;
                        break;
                    }
                    done.add(execute(a11y, obs, call));
                }
                long tAct = System.currentTimeMillis();
                long total = tAct - t0;
                stepMs.add(total);

                RunLog.line(String.format(Locale.US,
                        "#%d %s | obs %dms llm %dms act %dms = %.1fs | tok %d/%d | %s",
                        step, clock(t0), tObs - t0, tLlm - tObs, tAct - tLlm, total / 1000f,
                        r.promptTokens, r.completionTokens,
                        done.isEmpty() ? "(no tool call)" : android.text.TextUtils.join(" ; ", done)));
                if (!reasoning.isEmpty()) RunLog.line("   " + clip(reasoning, 240));

                remember(step, t0, obs.packageName, reasoning, done);
                if (finished) break;
                if (step == maxSteps) {
                    outcome = "failed";
                    answer = "Step cap (" + maxSteps + ") reached.";
                }
            }
        } catch (Exception e) {
            outcome = "error";
            answer = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        summary(outcome, answer);
    }

    // ------------------------------------------------------------------ prompt

    private String systemPrompt() {
        return "# ROLE & OBJECTIVE\n"
                + "You are an autonomous and highly efficient Android Device Execution Agent running on the phone itself. "
                + "Your goal is to accomplish the user's objective on the device.\n\n"
                + "**Objective: " + goal + "**\n\n"
                + "# 1. COGNITIVE PROTOCOL\n"
                + "In every turn, think through observation, planning and action in that order. Before calling a tool, "
                + "write one short paragraph of connected prose reflecting those three phases; it is kept in the execution history.\n"
                + "- Turn 1 only: briefly decompose the objective into high-level visual milestones.\n\n"
                + "# 2. DYNAMIC ENVIRONMENT\n"
                + "The device keeps changing while you think (3-7 s turn latency). If the screen progressed further than "
                + "expected, adapt instead of treating it as an error. For loading spinners or transitions, use `wait`.\n\n"
                + "# 3. HISTORY\n"
                + "You see a short text log of your last steps (`Step N [T+mm:ss]`) and only the latest screenshot. "
                + "Older steps are dropped, so write any value you will need later (codes, numbers, titles) into your reasoning.\n\n"
                + "# 4. STAGNATION\n"
                + "If the same action on the same target twice produced no substantial change, stop retrying it and choose "
                + "another path (scroll, back, different element, open the app again).\n\n"
                + "# 5. ACTION RULES\n"
                + "1. Prefer the element index. The `--- Visible UI Elements ---` list numbers every element (`[3] Text: 'Wi-Fi' | Bounds: ...`). "
                + "When the target is listed, pass `index`. Indices belong to the current observation only; never reuse an index from an earlier turn. "
                + "Within one turn every index refers to the current list, even after an earlier action in the same turn.\n"
                + "2. Otherwise use normalized coordinates `x`,`y` on a 0-1000 scale (of screen width / height) and give `target_description`. "
                + "Element bounds in the list use the same 0-1000 scale.\n"
                + "3. To start an app, use `open_app` (package name or app label); do not hunt for it on home screens.\n"
                + "4. You may call up to " + MAX_ACTIONS_PER_TURN + " tools per turn; they run in order.\n\n"
                + "# 6. TERMINATION (`finish`)\n"
                + "- Call `finish(status=\"completed\", answer=...)` only after the latest screen visibly shows the objective is achieved; "
                + "put the requested information in `answer`.\n"
                + "- Call `finish(status=\"failed\", answer=...)` with the exact blocking reason if the task is impossible.";
    }

    private String buildUserPrompt(Observation obs, int step, String nudge) {
        StringBuilder sb = new StringBuilder();
        if (!history.isEmpty()) {
            sb.append("# EXECUTION HISTORY (last ").append(history.size()).append(" steps)\n");
            for (String h : history) sb.append(h).append('\n');
            sb.append('\n');
        }
        if (nudge != null) sb.append(nudge).append("\n\n");
        sb.append("# CURRENT OBSERVATION [").append(clock(System.currentTimeMillis())).append("] (step ")
                .append(step).append(" of max ").append(maxSteps).append(")\n");
        sb.append("Foreground app: ").append(obs.packageName.isEmpty() ? "unknown" : obs.packageName).append('\n');
        if (!sendScreenshot) sb.append("No screenshot is attached (text-only mode); rely on the element list.\n");
        else if (obs.jpeg == null) sb.append("Screenshot unavailable (").append(obs.screenshotError).append("); rely on the element list.\n");
        sb.append("--- Visible UI Elements ---\n").append(obs.elementsText());
        return sb.toString();
    }

    private void remember(int step, long t0, String pkg, String reasoning, List<String> done) {
        history.addLast("Step " + step + " [" + clock(t0) + "] (" + pkg + "): " + clip(reasoning, 300)
                + " -> " + (done.isEmpty() ? "(no tool call)" : android.text.TextUtils.join(" ; ", done)));
        while (history.size() > HISTORY_STEPS) history.removeFirst();
    }

    // ------------------------------------------------------------------ tools

    static JSONArray tools() {
        try {
            JSONObject idx = prop("integer", "Index of the element in the current Visible UI Elements list.");
            JSONObject x = prop("integer", "Normalized x (0-1000), used when the target is not listed.");
            JSONObject y = prop("integer", "Normalized y (0-1000), used when the target is not listed.");
            JSONObject desc = prop("string", "Short name of what you aim at (required with x,y).");
            return new JSONArray()
                    .put(tool("tap", "Tap an element (by index) or a point (x,y on 0-1000 scale).",
                            new JSONObject().put("index", idx).put("x", x).put("y", y).put("target_description", desc),
                            new String[0]))
                    .put(tool("type_text", "Replace the text of an input field. If index is given, the field is tapped first to focus it; otherwise the focused field is used.",
                            new JSONObject().put("text", prop("string", "Text to enter.")).put("index", idx),
                            new String[]{"text"}))
                    .put(tool("swipe", "Swipe the finger in a direction ('up' moves content up to reveal what is below). Starts from the element at index, or from the screen center.",
                            new JSONObject()
                                    .put("direction", new JSONObject().put("type", "string")
                                            .put("enum", new JSONArray().put("up").put("down").put("left").put("right")))
                                    .put("index", idx),
                            new String[]{"direction"}))
                    .put(tool("back", "Press the system Back button.", new JSONObject(), new String[0]))
                    .put(tool("home", "Go to the home screen.", new JSONObject(), new String[0]))
                    .put(tool("open_app", "Launch an app by package name (e.g. com.android.settings) or by its launcher label.",
                            new JSONObject().put("app", prop("string", "Package name or app label.")),
                            new String[]{"app"}))
                    .put(tool("wait", "Wait for the UI to settle.",
                            new JSONObject().put("ms", prop("integer", "Milliseconds, 200-10000.")),
                            new String[]{"ms"}))
                    .put(tool("finish", "End the task. Turn-ending.",
                            new JSONObject()
                                    .put("status", new JSONObject().put("type", "string")
                                            .put("enum", new JSONArray().put("completed").put("failed")))
                                    .put("answer", prop("string", "Final answer or failure reason.")),
                            new String[]{"status", "answer"}));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject prop(String type, String description) throws Exception {
        return new JSONObject().put("type", type).put("description", description);
    }

    private static JSONObject tool(String name, String description, JSONObject props, String[] required) throws Exception {
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", name).put("description", description)
                .put("parameters", new JSONObject().put("type", "object").put("properties", props).put("required", req)));
    }

    /** Executes one tool call; returns a short ledger entry ("tap(index=5 'Battery') ok"). */
    private String execute(AgentAccessibilityService s, Observation obs, ChatClient.ToolCall call) {
        JSONObject a = call.args;
        try {
            switch (call.name) {
                case "tap": {
                    float[] p = target(obs, a);
                    boolean ok = Gestures.tap(s, p[0], p[1], 3000);
                    settle(SETTLE_MS);
                    return "tap(" + describe(obs, a) + ") " + (ok ? "ok" : "FAILED: gesture cancelled");
                }
                case "type_text": {
                    if (a.has("index")) {
                        float[] p = target(obs, a);
                        Gestures.tap(s, p[0], p[1], 3000);
                        settle(400);
                    }
                    String text = a.optString("text");
                    boolean ok = Gestures.setText(s, text, false);
                    settle(SETTLE_MS);
                    return "type_text('" + clip(text, 40) + "'" + (a.has("index") ? ", index=" + a.optInt("index") : "")
                            + ") " + (ok ? "ok" : "FAILED: no editable field");
                }
                case "swipe": {
                    String dir = a.optString("direction", "up");
                    float cx = obs.screenW / 2f, cy = obs.screenH / 2f;
                    if (a.has("index")) {
                        float[] p = target(obs, a);
                        cx = p[0];
                        cy = p[1];
                    }
                    float dx = 0, dy = 0;
                    float dist = 0.3f;
                    switch (dir) {
                        case "up": dy = -obs.screenH * dist; break;
                        case "down": dy = obs.screenH * dist; break;
                        case "left": dx = -obs.screenW * dist; break;
                        default: dx = obs.screenW * dist; break;
                    }
                    float x1 = clampX(obs, cx - dx / 2), y1 = clampY(obs, cy - dy / 2);
                    float x2 = clampX(obs, cx + dx / 2), y2 = clampY(obs, cy + dy / 2);
                    boolean ok = Gestures.swipe(s, x1, y1, x2, y2, 300, 3000);
                    settle(SETTLE_MS);
                    return "swipe(" + dir + ") " + (ok ? "ok" : "FAILED");
                }
                case "back":
                case "home": {
                    boolean ok = Gestures.performGlobalAction(s, call.name);
                    settle(SETTLE_MS);
                    return call.name + "() " + (ok ? "ok" : "FAILED");
                }
                case "open_app": {
                    String app = a.optString("app");
                    String pkg = launch(s, app);
                    settle(1000);
                    return "open_app('" + app + "') " + (pkg != null ? "ok -> " + pkg : "FAILED: no launchable app matches");
                }
                case "wait": {
                    long ms = Math.max(200, Math.min(10_000, a.optLong("ms", 1000)));
                    settle(ms);
                    return "wait(" + ms + "ms) ok";
                }
                default:
                    return call.name + " FAILED: unknown tool";
            }
        } catch (Exception e) {
            return call.name + " FAILED: " + e.getMessage();
        }
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
            return "index=" + i + " " + clip(label, 40);
        }
        return "x=" + a.optInt("x") + ",y=" + a.optInt("y") + " '" + clip(a.optString("target_description"), 30) + "' (self-described)";
    }

    private static float clampX(Observation o, float v) { return Math.max(1, Math.min(o.screenW - 2, v)); }
    private static float clampY(Observation o, float v) { return Math.max(1, Math.min(o.screenH - 2, v)); }

    private static String launch(AgentAccessibilityService s, String app) {
        PackageManager pm = s.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(app);
        String pkg = app;
        if (intent == null) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(q, 0);
            ResolveInfo best = null;
            String want = app.trim().toLowerCase(Locale.ROOT);
            for (ResolveInfo ri : apps) {
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                if (label.equals(want)) { best = ri; break; }
                if (best == null && label.contains(want)) best = ri;
            }
            if (best == null) return null;
            pkg = best.activityInfo.packageName;
            intent = pm.getLaunchIntentForPackage(pkg);
            if (intent == null) return null;
        }
        // Start fresh (like Artemis manage_app launch), not on whatever page the app was left on.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        s.startActivity(intent);
        return pkg;
    }

    private void settle(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    // ------------------------------------------------------------------ summary

    private void summary(String outcome, String answer) {
        long total = System.currentTimeMillis() - startMs;
        RunLog.line("==================================");
        RunLog.line("Outcome: " + outcome);
        if (!answer.isEmpty()) RunLog.line("Answer: " + answer);
        RunLog.line(String.format(Locale.US, "Total %.1fs | %d steps | avg %.2fs/step | median step %.2fs",
                total / 1000f, steps, steps == 0 ? 0f : total / 1000f / steps, median(stepMs) / 1000f));
        RunLog.line(String.format(Locale.US, "LLM: median %.2fs, max %.2fs per call",
                median(llmMs) / 1000f, llmMs.isEmpty() ? 0f : Collections.max(llmMs) / 1000f));
        RunLog.line("Tokens: prompt " + promptTokens + ", completion " + completionTokens
                + ", total " + (promptTokens + completionTokens));
    }

    private static float median(List<Long> v) {
        if (v.isEmpty()) return 0f;
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2f;
    }

    private String clock(long now) {
        long sec = Math.max(0, (now - startMs) / 1000);
        return String.format(Locale.US, "T+%02d:%02d", sec / 60, sec % 60);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
