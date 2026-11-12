package com.artemis.ondevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One task run: what was asked, which model ran it, every step with its timings and
 * tokens, and how it ended. Written only by the loop thread (FlashLoop); the UI reads it
 * live through {@link RunState} or from disk through {@link RunStore}.
 */
final class RunRecord {

    static final String RUNNING = "running";
    static final String COMPLETED = "completed";
    static final String FAILED = "failed";
    static final String STOPPED = "stopped";
    static final String ERROR = "error";

    static final class Step {
        int index;
        long offsetMs;
        long obsMs, llmMs, actMs;
        int promptTokens, completionTokens;
        String app = "";
        String reasoning = "";
        List<String> actions = new ArrayList<>();
        /** Successful actions in replayable form (see {@link Skill#action}); a skill is built from these. */
        List<JSONObject> replay = new ArrayList<>();
        /** Done by replaying a saved skill, without a model call. */
        boolean replayed;
        boolean failedAction;
        boolean hasScreenshot;

        long totalMs() { return obsMs + llmMs + actMs; }

        JSONObject toJson() throws Exception {
            JSONArray a = new JSONArray();
            for (String s : actions) a.put(s);
            JSONArray rp = new JSONArray();
            for (JSONObject r : replay) rp.put(r);
            return new JSONObject().put("index", index).put("offset_ms", offsetMs)
                    .put("obs_ms", obsMs).put("llm_ms", llmMs).put("act_ms", actMs)
                    .put("prompt_tokens", promptTokens).put("completion_tokens", completionTokens)
                    .put("app", app).put("reasoning", reasoning).put("actions", a).put("replay", rp)
                    .put("replayed", replayed)
                    .put("failed_action", failedAction).put("has_screenshot", hasScreenshot);
        }

        static Step fromJson(JSONObject o) {
            Step s = new Step();
            s.index = o.optInt("index");
            s.offsetMs = o.optLong("offset_ms");
            s.obsMs = o.optLong("obs_ms");
            s.llmMs = o.optLong("llm_ms");
            s.actMs = o.optLong("act_ms");
            s.promptTokens = o.optInt("prompt_tokens");
            s.completionTokens = o.optInt("completion_tokens");
            s.app = o.optString("app");
            s.reasoning = o.optString("reasoning");
            JSONArray a = o.optJSONArray("actions");
            if (a != null) for (int i = 0; i < a.length(); i++) s.actions.add(a.optString(i));
            JSONArray rp = o.optJSONArray("replay");
            if (rp != null) for (int i = 0; i < rp.length(); i++) s.replay.add(rp.optJSONObject(i));
            s.replayed = o.optBoolean("replayed");
            s.failedAction = o.optBoolean("failed_action");
            s.hasScreenshot = o.optBoolean("has_screenshot");
            return s;
        }
    }

    String id;
    String task;
    String providerId;
    String model;
    int maxSteps;
    boolean screenshot;
    long startedAt;
    volatile long endedAt;
    volatile String status = RUNNING;
    volatile String answer = "";
    /** The saved skill this run replayed first, or "". */
    String skillId = "";
    /** The model's working memory at the end of the run (see {@link TaskMemory}). */
    TaskMemory memory = new TaskMemory();
    final List<Step> steps = new CopyOnWriteArrayList<>();

    static String newId() {
        return System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static RunRecord start(String id, String task, String providerId, String model, int maxSteps, boolean screenshot) {
        RunRecord r = new RunRecord();
        r.id = id;
        r.task = task;
        r.providerId = providerId;
        r.model = model;
        r.maxSteps = maxSteps;
        r.screenshot = screenshot;
        r.startedAt = System.currentTimeMillis();
        return r;
    }

    boolean isRunning() { return RUNNING.equals(status); }

    long durationMs() {
        return (endedAt > 0 ? endedAt : System.currentTimeMillis()) - startedAt;
    }

    Step lastStep() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1);
    }

    // ------------------------------------------------------------------ metrics

    int promptTokens() { int t = 0; for (Step s : steps) t += s.promptTokens; return t; }
    int completionTokens() { int t = 0; for (Step s : steps) t += s.completionTokens; return t; }
    int failedActions() { int n = 0; for (Step s : steps) if (s.failedAction) n++; return n; }
    int replayedSteps() { int n = 0; for (Step s : steps) if (s.replayed) n++; return n; }
    boolean hasReplayableActions() { for (Step s : steps) if (!s.replay.isEmpty()) return true; return false; }

    long avgStepMs() { return steps.isEmpty() ? 0 : durationMs() / steps.size(); }
    long medianStepMs() { List<Long> v = new ArrayList<>(); for (Step s : steps) v.add(s.totalMs()); return pct(v, 50); }
    long medianLlmMs() { return pct(llm(), 50); }
    long p90LlmMs() { return pct(llm(), 90); }
    long maxLlmMs() { List<Long> v = llm(); return v.isEmpty() ? 0 : Collections.max(v); }
    long avgObsMs() { long t = 0; for (Step s : steps) t += s.obsMs; return steps.isEmpty() ? 0 : t / steps.size(); }
    long avgActMs() { long t = 0; for (Step s : steps) t += s.actMs; return steps.isEmpty() ? 0 : t / steps.size(); }
    long totalLlmMs() { long t = 0; for (Step s : steps) t += s.llmMs; return t; }

    /** Model latencies of the steps that called the model (replayed steps did not). */
    private List<Long> llm() { List<Long> v = new ArrayList<>(); for (Step s : steps) if (!s.replayed) v.add(s.llmMs); return v; }

    /** Nearest-rank percentile. */
    private static long pct(List<Long> v, int p) {
        if (v.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        int rank = (int) Math.ceil(p / 100.0 * s.size());
        return s.get(Math.max(0, rank - 1));
    }

    // ------------------------------------------------------------------ json

    JSONObject toJson() throws Exception {
        JSONArray st = new JSONArray();
        for (Step s : steps) st.put(s.toJson());
        return new JSONObject().put("id", id).put("task", task).put("provider", providerId)
                .put("model", model).put("max_steps", maxSteps).put("screenshot", screenshot)
                .put("started_at", startedAt).put("ended_at", endedAt)
                .put("status", status).put("answer", answer).put("skill", skillId)
                .put("memory", memory.toJson()).put("steps", st);
    }

    static RunRecord fromJson(JSONObject o) {
        RunRecord r = new RunRecord();
        r.id = o.optString("id");
        r.task = o.optString("task");
        r.providerId = o.optString("provider");
        r.model = o.optString("model");
        r.maxSteps = o.optInt("max_steps");
        r.screenshot = o.optBoolean("screenshot", true);
        r.startedAt = o.optLong("started_at");
        r.endedAt = o.optLong("ended_at");
        r.status = o.optString("status", ERROR);
        r.answer = o.optString("answer");
        r.skillId = o.optString("skill");
        r.memory = TaskMemory.fromJson(o.optJSONObject("memory"));
        JSONArray st = o.optJSONArray("steps");
        if (st != null) for (int i = 0; i < st.length(); i++) r.steps.add(Step.fromJson(st.optJSONObject(i)));
        // A run that was still "running" when the process died never finished.
        if (r.isRunning()) {
            r.status = ERROR;
            r.answer = "The app was closed while this task was running.";
            if (r.endedAt == 0) r.endedAt = r.startedAt + (r.lastStep() == null ? 0
                    : r.lastStep().offsetMs + r.lastStep().totalMs());
        }
        return r;
    }
}
