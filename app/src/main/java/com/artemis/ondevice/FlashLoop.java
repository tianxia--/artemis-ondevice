package com.artemis.ondevice;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The agent loop, on the phone: observe (screenshot + indexed element list) -> one model
 * call with tools -> execute the tool calls -> repeat until {@code finish}, Stop, or the
 * optional step cap. Element index first, 0-1000 normalized coordinates as the fallback;
 * no step cap by default, and when a cap is set, a final turn that may only call finish.
 *
 * Every step is recorded in a {@link RunRecord} (persisted by {@link RunStore}); logcat
 * only gets timings and action names, never the task, reasoning, answer or typed text.
 */
final class FlashLoop {

    static final int HISTORY_STEPS = 6;
    static final int MAX_ACTIONS_PER_TURN = 3;
    static final int MAX_OUTPUT_TOKENS = 2048;
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

    /**
     * Consecutive steps whose actions all failed: a warning at {@link #FAIL_WARN}, the run ends
     * as failed at {@link #FAIL_STOP} (the model keeps hitting errors instead of changing course).
     */
    static final int FAIL_WARN = 3;
    static final int FAIL_STOP = 5;
    private static final String FAIL_WARNING =
            "[WARNING] Every action in your last " + FAIL_WARN + " steps failed. Read the FAILED reasons in the "
                    + "history and change approach (another element, scroll, back, open_app), or call "
                    + "finish(status=\"failed\") with the blocking reason.";

    /**
     * Apps whose risk controls flag accessibility automation and can ban the user's account (a
     * WeChat test run nearly got the account banned). The agent never operates them: a run that
     * lands in one ends as failed, and open_app refuses to launch them.
     */
    static final Set<String> BLOCKED_APPS = new HashSet<>(Arrays.asList(
            "com.tencent.mm", "com.tencent.mobileqq", "com.eg.android.AlipayGphone"));

    /** The same apps named in a task ("微信读书", "QQ音乐" etc. are other apps and allowed). */
    private static final java.util.regex.Pattern BLOCKED_IN_TASK = java.util.regex.Pattern.compile(
            "微信(?!读书)|支付宝|wechat|weixin|alipay|(?<![a-z])qq(?!音乐|邮箱|浏览器|阅读)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    static boolean namesBlockedApp(String task) {
        return task != null && BLOCKED_IN_TASK.matcher(task).find();
    }

    /** After a failed or skipped action: re-orient against the task memory before going on. */
    private static final String RECOVER_NOTICE =
            "[NOTICE] An action in your last step failed or was skipped. Before acting, re-orient: compare the current "
                    + "screen with TASK MEMORY to see which part of the objective you are on. Do not redo parts listed as "
                    + "completed, and do not trust conclusions you drew in the failed step.";

    /** Actions that may move to another screen; x,y targets after them in the same turn are stale. */
    private static final Set<String> NAVIGATING = new HashSet<>(Arrays.asList(
            "tap", "long_press", "swipe", "back", "home", "open_app"));
    static final String MEMORY_TOOL = "update_memory";

    /** How long replay waits for a recorded target to appear (the screen may still be moving). */
    private static final long REPLAY_FIND_MS = 4000;
    private static final long REPLAY_POLL_MS = 250;


    interface StopSignal {
        boolean stopped();
    }

    private final Context ctx;
    private final ChatClient client;
    private final RunRecord run;
    private final StopSignal stop;
    /** Saved skill to replay before the model takes over, or null. */
    private final Skill skill;
    /** Facts and dead ends from the latest earlier run of the same task, as a prompt section, or "". */
    private String earlierNotes = "";
    private final String uiLanguage = uiLanguage();
    private boolean replayComplete;
    private final Deque<String> history = new ArrayDeque<>();
    private final Deque<String> recentStates = new ArrayDeque<>();

    FlashLoop(Context ctx, ChatClient client, RunRecord run, StopSignal stop, Skill skill) {
        this.ctx = ctx;
        this.client = client;
        this.run = run;
        this.stop = stop;
        this.skill = skill;
    }

    void run() {
        String status = RunRecord.STOPPED;
        String answer = "";
        String nudge = null;
        int failStreak = 0;
        if (namesBlockedApp(run.task)) {
            Log.w(AgentService.TAG, "task names a blocked app; not starting");
            run.answer = ctx.getString(R.string.blocked_app);
            run.endedAt = System.currentTimeMillis();
            run.status = RunRecord.FAILED;
            return;
        }
        earlierNotes = earlierNotes();
        try {
            if (skill != null) nudge = replay();
            // Replayed steps cost no model call and do not count against the step cap.
            int replayed = run.steps.size();
            for (int turn = 1; run.maxSteps <= 0 || turn <= run.maxSteps; turn++) {
                int step = replayed + turn;
                if (stop.stopped()) break;
                AgentAccessibilityService a11y = AgentAccessibilityService.get();
                if (a11y == null) {
                    status = RunRecord.ERROR;
                    answer = "The accessibility service is off. Turn on Artemis On-Device in Accessibility settings.";
                    break;
                }
                boolean finalTurn = run.maxSteps > 0 && turn == run.maxSteps;
                waitWhilePaused();
                long t0 = System.currentTimeMillis();
                RunState.phase(RunState.Phase.OBSERVING);
                Observation obs = Observation.capture(a11y);
                long tObs = System.currentTimeMillis();
                if (BLOCKED_APPS.contains(obs.packageName)) {
                    Log.w(AgentService.TAG, "blocked app in front; stopping");
                    status = RunRecord.FAILED;
                    answer = ctx.getString(R.string.blocked_app);
                    break;
                }

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
                // The memory describes what the current screen confirms, so it is applied before the actions.
                for (ChatClient.ToolCall call : r.toolCalls) {
                    if (!MEMORY_TOOL.equals(call.name)) continue;
                    run.memory.update(call.args);
                    rec.actions.add(MEMORY_TOOL + "() ok");
                }
                int n = 0;
                int okActions = 0;
                long pausedMs = 0;
                boolean navigated = false;
                boolean blocked = false;
                for (ChatClient.ToolCall call : r.toolCalls) {
                    if (MEMORY_TOOL.equals(call.name)) continue;
                    if (n++ >= MAX_ACTIONS_PER_TURN) break;
                    if (blocked) {
                        rec.actions.add(call.name + " SKIPPED: an earlier action in this turn failed");
                        continue;
                    }
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
                    String outcome;
                    Observation target = obs;
                    ChatClient.ToolCall act = call;
                    if (navigated && !hasIndex(call) && (call.args.has("x") || call.args.has("y"))) {
                        // A point chosen on the old screen; after a screen change it lands somewhere else.
                        outcome = call.name + " SKIPPED: x,y were chosen before an earlier action in this turn may have "
                                + "changed the screen; observe again";
                    } else {
                        if (hasIndex(call)) {
                            // The screen can move while the model thinks or after an earlier action:
                            // act on the same element in a fresh read, found again by its label.
                            target = Observation.capture(a11y, false);
                            act = retarget(obs, target, call);
                        }
                        outcome = act == null
                                ? call.name + " SKIPPED: the target is no longer on screen (the screen changed); observe again"
                                : Actions.execute(a11y, target, act);
                    }
                    if (outcome.contains("FAILED") || outcome.contains("SKIPPED")) {
                        rec.failedAction = true;
                        blocked = true;
                    } else {
                        okActions++;
                        JSONObject replayable = Skill.action(target, act, outcome);
                        if (replayable != null) rec.replay.add(replayable);
                    }
                    if (NAVIGATING.contains(call.name)) navigated = true;
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
                failStreak = rec.failedAction && okActions == 0 ? failStreak + 1 : 0;
                if (failStreak >= FAIL_STOP) {
                    Log.w(AgentService.TAG, "all actions failed " + failStreak + " steps in a row; stopping");
                    status = RunRecord.FAILED;
                    answer = ctx.getString(R.string.stopped_failures, failStreak);
                    break;
                }
                if (failStreak == FAIL_WARN && nudge == null) nudge = FAIL_WARNING;
                if (rec.failedAction && nudge == null) nudge = RECOVER_NOTICE;
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
        if (skill != null && !RunRecord.STOPPED.equals(status)) {
            SkillStore.recordOutcome(ctx, skill.id, replayComplete && RunRecord.COMPLETED.equals(status));
        }
        run.answer = answer;
        run.endedAt = System.currentTimeMillis();
        run.status = status;
        Log.i(AgentService.TAG, String.format(Locale.US, "run %s: %s | %d steps | %.1fs | tokens %d/%d",
                run.id, status, run.steps.size(), run.durationMs() / 1000f, run.promptTokens(), run.completionTokens()));
    }

    // ------------------------------------------------------------------ skill replay

    /**
     * Replays the skill's actions without model calls, each one recorded as a step. Stops at the
     * first action whose app or target is not on screen, or that fails; the model then carries on
     * from there. Returns the notice for the model's first turn.
     */
    private String replay() throws InterruptedException {
        run.skillId = skill.id;
        int total = skill.actions.size();
        for (int i = 0; i < total; i++) {
            if (stop.stopped()) return null;
            waitWhilePaused();
            AgentAccessibilityService a11y = AgentAccessibilityService.get();
            if (a11y == null) return null;
            JSONObject act = skill.actions.get(i);
            long t0 = System.currentTimeMillis();
            RunState.phase(RunState.Phase.OBSERVING);
            Observation obs;
            int index;
            boolean appOk;
            long deadline = t0 + REPLAY_FIND_MS;
            while (true) {
                obs = Observation.capture(a11y, false);
                index = Skill.find(obs, act);
                appOk = !Skill.needsApp(act) || act.optString("app").equals(obs.packageName);
                if ((appOk && index != Skill.NOT_FOUND) || System.currentTimeMillis() > deadline) break;
                Thread.sleep(REPLAY_POLL_MS);
            }
            if (BLOCKED_APPS.contains(obs.packageName)) return null;
            if (!appOk || index == Skill.NOT_FOUND) {
                Log.i(AgentService.TAG, "replay: step " + (i + 1) + "/" + total + " target not on screen; model takes over");
                return replayNotice(i, total, !appOk
                        ? "the screen is in " + obs.packageName + ", the recorded step expected " + act.optString("app")
                        : "the recorded target '" + clip(act.optString("key"), 60) + "' is not on screen");
            }
            long tObs = System.currentTimeMillis();
            RunState.phase(RunState.Phase.ACTING);
            ChatClient.ToolCall call;
            try {
                call = Skill.toCall(act, index);
            } catch (Exception e) {
                return replayNotice(i, total, "the recorded step is unreadable");
            }
            String outcome = Actions.execute(a11y, obs, call);
            RunRecord.Step rec = new RunRecord.Step();
            rec.index = run.steps.size() + 1;
            rec.offsetMs = t0 - run.startedAt;
            rec.app = obs.packageName;
            rec.replayed = true;
            rec.obsMs = tObs - t0;
            rec.actMs = System.currentTimeMillis() - tObs;
            rec.actions.add(outcome);
            boolean failed = outcome.contains("FAILED");
            if (failed) {
                rec.failedAction = true;
            } else {
                JSONObject again = Skill.action(obs, call, outcome);
                if (again != null) rec.replay.add(again);
            }
            run.steps.add(rec);
            RunState.stepAdded();
            saveProgress();
            remember(rec);
            Log.i(AgentService.TAG, String.format(Locale.US, "#%d replay obs %dms act %dms | %s",
                    rec.index, rec.obsMs, rec.actMs, call.name));
            if (failed) return replayNotice(i + 1, total, "the replayed action failed");
        }
        replayComplete = true;
        return "[REPLAY] Steps 1-" + total + " replayed a saved skill for this objective, without model calls. "
                + "Check the current screen: if it shows the objective is achieved, call finish with the answer; "
                + "otherwise continue the task from here.";
    }

    private static String replayNotice(int done, int total, String reason) {
        return "[REPLAY] " + (done == 0 ? "No step" : "Steps 1-" + done) + " of a saved " + total
                + "-step skill for this objective could be replayed (" + reason + "). "
                + "Continue the task from the current screen.";
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
                + "- Turn 1 only: briefly decompose the objective into its parts and record them with `update_memory` "
                + "(the first part under `next`).\n\n"
                + "# 2. DYNAMIC ENVIRONMENT\n"
                + "The device keeps changing while you think (1-5 s turn latency). If the screen progressed further than "
                + "expected, adapt instead of treating it as an error. For loading spinners or transitions, use `wait`.\n\n"
                + "# 3. HISTORY AND TASK MEMORY\n"
                + "You see a short text log of your last " + HISTORY_STEPS + " steps (`Step N [T+mm:ss]`) and only the latest "
                + "screenshot; older steps are dropped. TASK MEMORY is kept for the whole task and is your source of truth "
                + "about progress. Call `update_memory` (next to your action, in the same turn) whenever:\n"
                + "- the current screen confirms a part of the objective is done (add it to `completed`);\n"
                + "- you find something you will need later: where a feature is, a search term that works, a value to "
                + "report (add it to `facts`);\n"
                + "- a path proved useless: no results, feature not there, repeated no-change (add it to `dead_ends`, "
                + "and do not try it again);\n"
                + "- the plan changes (`next`).\n"
                + "Each list you pass replaces the old one, so pass the full list. Before concluding that something is "
                + "impossible or missing, check `facts` and `completed`: you may have found or done it already.\n\n"
                + "# 4. STAGNATION\n"
                + "If the same action on the same target twice produced no substantial change, stop retrying it and choose "
                + "another path (a different gesture such as long_press, scroll, back, a different element, reopen the app).\n\n"
                + "# 5. ACTION RULES\n"
                + "1. Prefer the element index. The `--- Visible UI Elements ---` list numbers every element (`[3] Text: 'Wi-Fi' | Bounds: ...`). "
                + "When the target is listed, pass `index`. Indices belong to the current observation only; never reuse an index from an earlier turn. "
                + "Within one turn every index refers to the current list; if an earlier action changes the screen, later "
                + "actions whose target is gone (and any x,y action after a tap, swipe, back, home or open_app) are skipped, "
                + "so only chain actions on the same screen (e.g. type_text then tap Send).\n"
                + "2. Otherwise use normalized coordinates `x`,`y` on a 0-1000 scale (of screen width / height) and give `target_description`. "
                + "Element bounds in the list use the same 0-1000 scale. The list can also contain elements of a page that is "
                + "covered by the page on top; trust the screenshot for what is actually visible.\n"
                + "3. Hold-to-act controls (\"hold to talk\", \"hold to record\", press-and-hold menus) need `long_press` with a "
                + "`duration_ms` long enough for the action (e.g. 3000 ms to record a short voice message; the finger lifts at the end, which "
                + "usually sends). A plain `tap` on them does nothing useful.\n"
                + "4. To start an app, use `open_app` (package name or app label); it opens on the app's start screen.\n"
                + "5. You may call up to " + MAX_ACTIONS_PER_TURN + " action tools per turn (update_memory does not count); "
                + "they run in order, and the rest of the turn stops at the first failed or skipped action.\n"
                + "6. If `type_text` reports no editable field, it has put your text on the clipboard: long-press the input "
                + "field and tap Paste (粘贴) instead of typing letter by letter on the keyboard. Never tap Paste at any other "
                + "time: the clipboard may hold the user's private data.\n"
                + "7. Language of searches and labels, in this order: (a) the language the current screen shows (each turn "
                + "says which script dominates the screen text; check the screenshot too); (b) the phone's system language ("
                + uiLanguage + ") when the screen has too little text to tell; (c) only if both fail, the objective's "
                + "language, synonyms and other paths. Translate the objective's terms into that language before searching "
                + "or looking for a menu item (e.g. 定时开关机 is 'Scheduled power on/off' on an English screen, and stays "
                + "定时开关机 on a Chinese one). Answer the user in the objective's language.\n\n"
                + "# 6. TERMINATION (`finish`)\n"
                + "- A part that involves another person (a call, a meeting invite, a friend request, a message) is done once "
                + "you have performed it. If they reject, decline, are busy or do not answer, that is still done: record it in "
                + "`completed` with the outcome (e.g. \"called Alex: he rejected the call\"), move on, and never place, send "
                + "or invite again. Mention the outcome in `answer`.\n"
                + "- Call `finish(status=\"completed\", answer=...)` once every part of the objective is done: parts listed "
                + "in TASK MEMORY as completed count as done and need not be visible again (never go back to re-check them); "
                + "the last part must be visible on the current screen. Put the requested information in `answer`, in the "
                + "language of the objective.\n"
                + "- Call `finish(status=\"failed\", answer=...)` with the exact blocking reason if the task is impossible.";
    }

    private String buildUserPrompt(Observation obs, int step, String notice) {
        StringBuilder sb = new StringBuilder(earlierNotes).append(run.memory.render()).append('\n');
        if (!history.isEmpty()) {
            sb.append("# EXECUTION HISTORY (last ").append(history.size()).append(" steps)\n");
            for (String h : history) sb.append(h).append('\n');
            sb.append('\n');
        }
        if (notice != null) sb.append(notice).append("\n\n");
        sb.append("# CURRENT OBSERVATION [").append(clock(System.currentTimeMillis())).append("] (step ").append(step);
        if (run.maxSteps > 0) sb.append(" of max ").append(run.maxSteps);
        sb.append(")\n");
        sb.append("Foreground app: ").append(obs.packageName.isEmpty() ? "unknown" : obs.packageName)
                .append(" | Screen text: ").append(screenScript(obs))
                .append(" | Phone language: ").append(uiLanguage).append('\n');
        if (!run.screenshot) sb.append("No screenshot is attached (text-only mode); rely on the element list.\n");
        else if (obs.jpeg == null) sb.append("Screenshot unavailable (").append(obs.screenshotError).append("); rely on the element list.\n");
        sb.append("--- Visible UI Elements ---\n").append(obs.elementsText());
        return sb.toString();
    }

    private void remember(RunRecord.Step s) {
        String thought = s.replayed ? "(replayed from a saved skill)"
                : s.failedAction ? "(this step failed; its reading of the screen may be wrong) " + clip(s.reasoning, 200)
                : clip(s.reasoning, 300);
        history.addLast("Step " + s.index + " [" + clock(run.startedAt + s.offsetMs) + "] (" + s.app + "): "
                + thought + " -> "
                + (s.actions.isEmpty() ? "(no tool call)" : TextUtils.join(" ; ", s.actions)));
        while (history.size() > HISTORY_STEPS) history.removeFirst();
    }

    /**
     * Which writing system dominates the screen's own text (quoted label text only, not the list's
     * English field names): a hint for the language to search and look for labels in.
     */
    static String screenScript(Observation obs) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        int total = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:Text|Desc|Hint): '([^']*)'").matcher("");
        for (Observation.Element e : obs.elements) {
            m.reset(e.label);
            while (m.find()) {
                String text = m.group(1);
                for (int i = 0; i < text.length(); ) {
                    int cp = text.codePointAt(i);
                    i += Character.charCount(cp);
                    if (!Character.isLetter(cp)) continue;
                    String script = scriptName(Character.UnicodeScript.of(cp));
                    counts.put(script, counts.getOrDefault(script, 0) + 1);
                    total++;
                }
            }
        }
        if (total < 10) return "too little text to tell";
        String top = null;
        for (java.util.Map.Entry<String, Integer> c : counts.entrySet()) {
            if (top == null || c.getValue() > counts.get(top)) top = c.getKey();
        }
        int pct = Math.round(100f * counts.get(top) / total);
        return pct >= 60 ? "mostly " + top + " (" + pct + "%)" : "mixed, largest " + top + " (" + pct + "%)";
    }

    private static String scriptName(Character.UnicodeScript s) {
        switch (s) {
            case HAN: return "Chinese characters";
            case LATIN: return "Latin script (e.g. English)";
            case HIRAGANA: case KATAKANA: return "Japanese kana";
            case HANGUL: return "Korean";
            case CYRILLIC: return "Cyrillic (e.g. Russian)";
            case ARABIC: return "Arabic";
            case THAI: return "Thai";
            case DEVANAGARI: return "Devanagari (e.g. Hindi)";
            default: return s.name();
        }
    }

    /** The phone's own language (not this app's in-app language choice). */
    private static String uiLanguage() {
        java.util.Locale l = android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
        return l.getDisplayName(java.util.Locale.ENGLISH) + " (" + l.toLanguageTag() + ")";
    }

    /**
     * What the latest earlier run of the same task (case, spaces and punctuation ignored) found
     * and ruled out, so a second run does not repeat the first one's detours.
     */
    private String earlierNotes() {
        String task = Skill.normalize(run.task);
        for (RunRecord r : RunStore.list(ctx)) {
            if (r.id.equals(run.id) || !Skill.normalize(r.task).equals(task)) continue;
            if (r.memory.facts.isEmpty() && r.memory.deadEnds.isEmpty()) continue;
            StringBuilder sb = new StringBuilder("# NOTES FROM AN EARLIER RUN OF THIS SAME TASK (it ended: ")
                    .append(r.status).append(")\n");
            for (String f : r.memory.facts) sb.append("- found: ").append(f).append('\n');
            for (String d : r.memory.deadEnds) sb.append("- dead end, skip it: ").append(d).append('\n');
            return sb.append("Start from what was found and skip the dead ends; the screen may differ, so check it.\n\n")
                    .toString();
        }
        return "";
    }

    private static boolean hasIndex(ChatClient.ToolCall call) {
        return call.args.has("index") && !call.args.isNull("index");
    }

    /**
     * The call aimed at the same element in a fresh read of the screen: same label, nearest to
     * where it was. Null when it is gone; the call unchanged when its index was invalid anyway
     * (the action then reports that).
     */
    private static ChatClient.ToolCall retarget(Observation old, Observation fresh, ChatClient.ToolCall call) {
        int i = call.args.optInt("index", -1);
        if (i < 0 || i >= old.elements.size()) return call;
        Observation.Element e = old.elements.get(i);
        int j = Skill.find(fresh, Skill.key(e.label), old.nx(e.cx()), old.ny(e.cy()));
        if (j == Skill.NOT_FOUND) {
            Log.i(AgentService.TAG, call.name + ": target " + i + " gone after the screen changed; skipped");
            return null;
        }
        if (j != i) Log.i(AgentService.TAG, call.name + ": target moved " + i + " -> " + j);
        try {
            return new ChatClient.ToolCall(call.name, new JSONObject(call.args.toString()).put("index", j));
        } catch (Exception ex) {
            return call;
        }
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
            JSONObject list = new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"));
            JSONObject memory = tool(MEMORY_TOOL, "Replace the task memory (kept for the whole task, shown at the top of "
                            + "every turn). Call it next to your action whenever progress, facts, dead ends or the plan change. "
                            + "Each list given replaces the old list; lists left out are kept.",
                    new JSONObject()
                            .put("completed", new JSONObject(list.toString()).put("description",
                                    "Parts of the objective the screen has confirmed done, e.g. \"message sent to Alex\"."))
                            .put("facts", new JSONObject(list.toString()).put("description",
                                    "Things found that later steps need: where a feature is, a working search term, values to report."))
                            .put("dead_ends", new JSONObject(list.toString()).put("description",
                                    "Paths that proved useless, e.g. \"Settings search for 关机: no results\"."))
                            .put("next", prop("string", "The next part to do.")),
                    new String[0]);
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
                    .put(memory)
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
