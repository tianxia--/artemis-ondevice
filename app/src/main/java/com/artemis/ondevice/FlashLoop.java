package com.artemis.ondevice;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The agent loop, on the phone: observe (screenshot + indexed element list)
 * -> one model call with tools -> execute the tool calls -> repeat until {@code finish},
 * Stop, or the optional step cap. Element index first, 0-1000 normalized
 * coordinates as the fallback; no step cap by default, and when a cap is set, a final turn that may only call finish.
 *
 * Every step is recorded in a {@link RunRecord} (persisted by {@link RunStore}); logcat
 * only gets timings and action names, never the task, reasoning, answer or typed text.
 */
final class FlashLoop {

    static final int HISTORY_STEPS = 6;
    static final int MAX_ACTIONS_PER_TURN = 3;
    static final int MAX_OUTPUT_TOKENS = 2048;
    private static final long SETTLE_MS = 500L;
    private static final String NO_TOOL_NUDGE =
            "You did not call any tools last turn. Please make progress by calling an action tool or 'finish'.";
    private static final String FINAL_TURN_WARNING =
            "[WARNING] This is your final turn; only 'finish' is available.";
    /**
     * No-progress guard (on top of the optional step cap, which is off by default): a
     * (screen, actions) pair seen {@link #REPEAT_WARN} times within the last
     * {@link #REPEAT_WINDOW} steps earns a warning, {@link #REPEAT_STOP} times ends the run
     * as failed. Counting repeats rather than "screen unchanged" also catches loops that
     * alternate between screens (e.g. Create -> Send -> back to Create).
     */
    static final int REPEAT_WINDOW = 20;
    static final int REPEAT_WARN = 3;
    static final int REPEAT_STOP = 4;
    private static final String REPEAT_WARNING =
            "[WARNING] You have now done the same action on the same screen " + REPEAT_WARN + " times without "
                    + "progress. Do not repeat it again: take a different path, or call finish(status=\"failed\") "
                    + "with the blocking reason. One more repeat ends the task.";

    private static final String CLIPBOARD_HANDOFF =
            "The text is now on the clipboard: long-press the input field, tap Paste (粘贴), and check the field before sending.";

    interface StopSignal {
        boolean stopped();
    }

    private final Context ctx;
    private final ChatClient client;
    private final RunRecord run;
    private final StopSignal stop;
    private final Deque<String> history = new ArrayDeque<>();
    private final Deque<String> recentStates = new ArrayDeque<>();

    FlashLoop(Context ctx, ChatClient client, RunRecord run, StopSignal stop) {
        this.ctx = ctx;
        this.client = client;
        this.run = run;
        this.stop = stop;
    }

    void run() {
        String status = RunRecord.STOPPED;
        String answer = "";
        String nudge = null;
        try {
            for (int step = 1; run.maxSteps <= 0 || step <= run.maxSteps; step++) {
                if (stop.stopped()) break;
                AgentAccessibilityService a11y = AgentAccessibilityService.get();
                if (a11y == null) {
                    status = RunRecord.ERROR;
                    answer = "The accessibility service is off. Turn on Artemis On-Device in Accessibility settings.";
                    break;
                }
                boolean finalTurn = run.maxSteps > 0 && step == run.maxSteps;
                waitWhilePaused();
                long t0 = System.currentTimeMillis();
                RunState.phase(RunState.Phase.OBSERVING);
                Observation obs = Observation.capture(a11y);
                long tObs = System.currentTimeMillis();

                RunState.phase(RunState.Phase.THINKING);
                String prompt = buildUserPrompt(obs, step, finalTurn ? FINAL_TURN_WARNING : nudge);
                ChatClient.Result r = client.complete(run.model, systemPrompt(), prompt,
                        run.screenshot ? obs.jpeg : null, tools(finalTurn), MAX_OUTPUT_TOKENS);
                long tLlm = System.currentTimeMillis();
                if (stop.stopped()) break;

                RunState.phase(RunState.Phase.ACTING);
                RunRecord.Step rec = new RunRecord.Step();
                rec.index = step;
                rec.offsetMs = t0 - run.startedAt;
                rec.app = obs.packageName;
                rec.reasoning = r.text == null ? "" : r.text.trim();
                rec.promptTokens = r.promptTokens;
                rec.completionTokens = r.completionTokens;

                boolean finished = false;
                nudge = r.toolCalls.isEmpty() ? NO_TOOL_NUDGE : null;
                int n = 0;
                long pausedMs = 0;
                for (ChatClient.ToolCall call : r.toolCalls) {
                    if (n++ >= MAX_ACTIONS_PER_TURN) break;
                    pausedMs += waitWhilePaused();
                    if (stop.stopped()) break;
                    if ("finish".equals(call.name)) {
                        status = "failed".equals(call.args.optString("status")) ? RunRecord.FAILED : RunRecord.COMPLETED;
                        answer = call.args.optString("answer", "").trim();
                        // A reply cut off at the token limit can carry finish with an empty answer.
                        if (answer.isEmpty() && !rec.reasoning.isEmpty()) {
                            Log.w(AgentService.TAG, "finish without answer; using the step's reasoning");
                            answer = rec.reasoning;
                        }
                        rec.actions.add("finish(" + status + ")");
                        finished = true;
                        break;
                    }
                    String outcome = execute(a11y, obs, call);
                    if (outcome.contains("FAILED")) rec.failedAction = true;
                    rec.actions.add(outcome);
                }
                long tAct = System.currentTimeMillis();
                rec.obsMs = tObs - t0;
                rec.llmMs = tLlm - tObs;
                rec.actMs = tAct - tLlm - pausedMs;
                if (obs.jpeg != null) {
                    try {
                        RunStore.saveScreenshot(ctx, run.id, step, obs.jpeg);
                        rec.hasScreenshot = true;
                    } catch (Exception e) {
                        Log.w(AgentService.TAG, "step screenshot not saved: " + e.getMessage());
                    }
                }
                run.steps.add(rec);
                RunState.stepAdded();
                saveProgress();
                Log.i(AgentService.TAG, String.format(Locale.US,
                        "#%d obs %dms llm %dms act %dms | tok %d/%d | %s", step, rec.obsMs, rec.llmMs, rec.actMs,
                        rec.promptTokens, rec.completionTokens, actionNames(r.toolCalls)));

                remember(rec);
                if (finished) break;
                int repeats = countRepeat(obs, rec);
                if (repeats >= REPEAT_STOP) {
                    Log.w(AgentService.TAG, "no progress: same screen+action " + repeats + "x; stopping");
                    status = RunRecord.FAILED;
                    answer = ctx.getString(R.string.stopped_no_progress, REPEAT_WINDOW, repeats);
                    break;
                }
                if (repeats == REPEAT_WARN && nudge == null) nudge = REPEAT_WARNING;
                if (finalTurn) {
                    status = RunRecord.FAILED;
                    answer = "Reached the step limit (" + run.maxSteps + ") before the task finished.";
                }
            }
        } catch (InterruptedException e) {
            status = RunRecord.STOPPED;
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (stop.stopped()) {
                status = RunRecord.STOPPED;
            } else {
                status = RunRecord.ERROR;
                answer = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        if (RunRecord.STOPPED.equals(status) && answer.isEmpty()) answer = ctx.getString(R.string.stopped_by_you);
        run.answer = answer;
        run.endedAt = System.currentTimeMillis();
        run.status = status;
        Log.i(AgentService.TAG, String.format(Locale.US, "run %s: %s | %d steps | %.1fs | tokens %d/%d",
                run.id, status, run.steps.size(), run.durationMs() / 1000f, run.promptTokens(), run.completionTokens()));
    }

    /**
     * The user opened the bubble panel: hold here until it is closed or the run is stopped, so
     * the screen stays still while they look and Stop is never raced by the next action.
     * Time spent paused is not attributed to any step phase.
     */
    private long waitWhilePaused() throws InterruptedException {
        long t0 = System.currentTimeMillis();
        while (RunState.paused() && !stop.stopped()) Thread.sleep(100);
        return System.currentTimeMillis() - t0;
    }

    /** Keeps the record on disk current, so a run cut short (process killed) keeps its steps. */
    private void saveProgress() {
        try {
            RunStore.save(ctx, run);
        } catch (Exception e) {
            Log.w(AgentService.TAG, "run progress not saved: " + e.getMessage());
        }
    }

    private static String actionNames(List<ChatClient.ToolCall> calls) {
        if (calls.isEmpty()) return "(no tool call)";
        List<String> names = new ArrayList<>();
        for (ChatClient.ToolCall c : calls) names.add(c.name);
        return TextUtils.join(",", names);
    }

    // ------------------------------------------------------------------ prompt

    private String systemPrompt() {
        return "# ROLE & OBJECTIVE\n"
                + "You are an autonomous and highly efficient Android Device Execution Agent running on the phone itself. "
                + "Your goal is to accomplish the user's objective on the device.\n\n"
                + "**Objective: " + run.task + "**\n\n"
                + "# 1. COGNITIVE PROTOCOL\n"
                + "In every turn, think through observation, planning and action in that order. Before calling a tool, "
                + "write one short paragraph of connected prose reflecting those three phases; it is kept in the execution history.\n"
                + "- Turn 1 only: briefly decompose the objective into high-level visual milestones.\n\n"
                + "# 2. DYNAMIC ENVIRONMENT\n"
                + "The device keeps changing while you think (1-5 s turn latency). If the screen progressed further than "
                + "expected, adapt instead of treating it as an error. For loading spinners or transitions, use `wait`.\n\n"
                + "# 3. HISTORY\n"
                + "You see a short text log of your last steps (`Step N [T+mm:ss]`) and only the latest screenshot. "
                + "Older steps are dropped, so write any value you will need later (codes, numbers, titles) into your reasoning.\n\n"
                + "# 4. STAGNATION\n"
                + "If the same action on the same target twice produced no substantial change, stop retrying it and choose "
                + "another path (a different gesture such as long_press, scroll, back, a different element, reopen the app).\n\n"
                + "# 5. ACTION RULES\n"
                + "1. Prefer the element index. The `--- Visible UI Elements ---` list numbers every element (`[3] Text: 'Wi-Fi' | Bounds: ...`). "
                + "When the target is listed, pass `index`. Indices belong to the current observation only; never reuse an index from an earlier turn. "
                + "Within one turn every index refers to the current list, even after an earlier action in the same turn.\n"
                + "2. Otherwise use normalized coordinates `x`,`y` on a 0-1000 scale (of screen width / height) and give `target_description`. "
                + "Element bounds in the list use the same 0-1000 scale. The list can also contain elements of a page that is "
                + "covered by the page on top; trust the screenshot for what is actually visible.\n"
                + "3. Hold-to-act controls (\"hold to talk\", \"hold to record\", press-and-hold menus) need `long_press` with a "
                + "`duration_ms` long enough for the action (e.g. 3000 ms to record a short voice message; the finger lifts at the end, which "
                + "usually sends). A plain `tap` on them does nothing useful.\n"
                + "4. To start an app, use `open_app` (package name or app label); it opens on the app's start screen.\n"
                + "5. You may call up to " + MAX_ACTIONS_PER_TURN + " tools per turn; they run in order.\n"
                + "6. If `type_text` reports no editable field, it has put your text on the clipboard: long-press the input "
                + "field and tap Paste (粘贴) instead of typing letter by letter on the keyboard. Never tap Paste at any other "
                + "time: the clipboard may hold the user's private data.\n\n"
                + "# 6. TERMINATION (`finish`)\n"
                + "- Call `finish(status=\"completed\", answer=...)` only after the latest screen visibly shows the objective is achieved; "
                + "put the requested information in `answer`, in the language of the objective.\n"
                + "- Call `finish(status=\"failed\", answer=...)` with the exact blocking reason if the task is impossible.";
    }

    private String buildUserPrompt(Observation obs, int step, String notice) {
        StringBuilder sb = new StringBuilder();
        if (!history.isEmpty()) {
            sb.append("# EXECUTION HISTORY (last ").append(history.size()).append(" steps)\n");
            for (String h : history) sb.append(h).append('\n');
            sb.append('\n');
        }
        if (notice != null) sb.append(notice).append("\n\n");
        sb.append("# CURRENT OBSERVATION [").append(clock(System.currentTimeMillis())).append("] (step ").append(step);
        if (run.maxSteps > 0) sb.append(" of max ").append(run.maxSteps);
        sb.append(")\n");
        sb.append("Foreground app: ").append(obs.packageName.isEmpty() ? "unknown" : obs.packageName).append('\n');
        if (!run.screenshot) sb.append("No screenshot is attached (text-only mode); rely on the element list.\n");
        else if (obs.jpeg == null) sb.append("Screenshot unavailable (").append(obs.screenshotError).append("); rely on the element list.\n");
        sb.append("--- Visible UI Elements ---\n").append(obs.elementsText());
        return sb.toString();
    }

    private void remember(RunRecord.Step s) {
        history.addLast("Step " + s.index + " [" + clock(run.startedAt + s.offsetMs) + "] (" + s.app + "): "
                + clip(s.reasoning, 300) + " -> "
                + (s.actions.isEmpty() ? "(no tool call)" : TextUtils.join(" ; ", s.actions)));
        while (history.size() > HISTORY_STEPS) history.removeFirst();
    }

    /**
     * Records this step's (screen, actions) state and returns how often it occurs in the last
     * {@link #REPEAT_WINDOW} steps. Digits are dropped so clocks, timers and shifting element
     * indices do not make an otherwise identical state look new.
     */
    private int countRepeat(Observation obs, RunRecord.Step rec) {
        StringBuilder s = new StringBuilder(obs.packageName).append('\n');
        for (Observation.Element e : obs.elements) s.append(e.label).append('\n');
        s.append("=>").append(TextUtils.join(";", rec.actions));
        String state = s.toString().replaceAll("\\d", "");
        recentStates.addLast(state);
        while (recentStates.size() > REPEAT_WINDOW) recentStates.removeFirst();
        int count = 0;
        for (String st : recentStates) if (st.equals(state)) count++;
        return count;
    }

    // ------------------------------------------------------------------ tools

    static JSONArray tools(boolean finishOnly) {
        try {
            JSONObject idx = prop("integer", "Index of the element in the current Visible UI Elements list.");
            JSONObject x = prop("integer", "Normalized x (0-1000), used when the target is not listed.");
            JSONObject y = prop("integer", "Normalized y (0-1000), used when the target is not listed.");
            JSONObject desc = prop("string", "Short name of what you aim at (required with x,y).");
            JSONObject finish = tool("finish", "End the task. Turn-ending.",
                    new JSONObject()
                            .put("status", new JSONObject().put("type", "string")
                                    .put("enum", new JSONArray().put("completed").put("failed")))
                            .put("answer", prop("string", "Final answer or failure reason.")),
                    new String[]{"status", "answer"});
            if (finishOnly) return new JSONArray().put(finish);
            return new JSONArray()
                    .put(tool("tap", "Tap an element (by index) or a point (x,y on 0-1000 scale).",
                            new JSONObject().put("index", idx).put("x", x).put("y", y).put("target_description", desc),
                            new String[0]))
                    .put(tool("long_press", "Press and hold an element or point, then lift. Use for hold-to-talk / hold-to-record buttons and long-press menus.",
                            new JSONObject().put("index", idx).put("x", x).put("y", y).put("target_description", desc)
                                    .put("duration_ms", prop("integer", "How long to hold, 500-10000 ms (default 1000).")),
                            new String[0]))
                    .put(tool("type_text", "Replace the text of an input field. If index is given, the field is tapped first to focus it; otherwise the focused field is used.",
                            new JSONObject().put("text", prop("string", "Text to enter.")).put("index", idx),
                            new String[]{"text"}))
                    .put(tool("swipe", "Swipe the finger in a direction ('up' moves content up to reveal what is below). Starts from the element at index, or from the screen center.",
                            new JSONObject()
                                    .put("direction", new JSONObject().put("type", "string")
                                            .put("enum", new JSONArray().put("up").put("down").put("left").put("right")))
                                    .put("distance", new JSONObject().put("type", "string")
                                            .put("enum", new JSONArray().put("short").put("medium").put("long"))
                                            .put("description", "short = 15%, medium = 30% (default), long = 60% of the screen."))
                                    .put("index", idx),
                            new String[]{"direction"}))
                    .put(tool("back", "Press the system Back button.", new JSONObject(), new String[0]))
                    .put(tool("home", "Go to the home screen.", new JSONObject(), new String[0]))
                    .put(tool("open_app", "Launch an app on its start screen, by package name (e.g. com.android.settings) or launcher label.",
                            new JSONObject().put("app", prop("string", "Package name or app label.")),
                            new String[]{"app"}))
                    .put(tool("wait", "Wait for the UI to settle.",
                            new JSONObject().put("ms", prop("integer", "Milliseconds, 200-10000.")),
                            new String[]{"ms"}))
                    .put(finish);
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
    private String execute(AgentAccessibilityService s, Observation obs, ChatClient.ToolCall call)
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
                    String head = "type_text('" + clip(text, 40) + "'" + (a.has("index") ? ", index=" + a.optInt("index") : "") + ") ";
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

    private static void settle(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private String clock(long now) {
        long sec = Math.max(0, (now - run.startedAt) / 1000);
        return String.format(Locale.US, "T+%02d:%02d", sec / 60, sec % 60);
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
