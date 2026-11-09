package com.artemis.ondevice;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The MCP tools. High level: hand a task to the phone's own agent (run_task, get_run, stop_run,
 * list_runs, get_step_screenshot, list_skills). Low level: observe the screen and act on it with
 * the same actions the agent uses, so the client's model drives the phone itself.
 */
final class McpTools {

    private static final int MAX_WAIT_S = 120;
    private static final int STEP_TEXT_MAX = 300;
    /** Run ids are "<millis>-<8 hex>"; anything else never reaches the file system. */
    private static final String RUN_ID = "\\d{10,16}-[0-9a-f]{8}";
    /** Actions that leave or never touch the screen in front, so they are allowed on our own screens. */
    private static final Set<String> SCREEN_INDEPENDENT = new HashSet<>(Arrays.asList("back", "home", "open_app", "wait"));

    private final Context ctx;
    /** The latest observe result; element indices in action calls refer to it. Guarded by {@code screen}. */
    private Observation lastObservation;
    /** observe and the actions go one at a time; run tools don't wait for them (stop_run must stay free). */
    private final Object screen = new Object();

    McpTools(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ------------------------------------------------------------------ list

    JSONArray list() throws Exception {
        JSONArray out = new JSONArray()
                .put(tool("run_task", "Let the phone's own agent do a task described in natural language (it uses the "
                                + "model configured on the phone, and replays a saved skill when one matches). Returns the "
                                + "run id; with wait_seconds it waits for the result.",
                        props().put("task", prop("string", "The task, e.g. \"Open Settings and tell me the battery level\"."))
                                .put("wait_seconds", prop("integer", "Wait up to this long for the run to end (0-" + MAX_WAIT_S + ", default 0).")),
                        "task"))
                .put(tool("get_run", "Status, answer, timing and steps of a run.",
                        props().put("run_id", prop("string", "Run id from run_task or list_runs."))
                                .put("include_steps", prop("boolean", "Include the step-by-step record (default true).")),
                        "run_id"))
                .put(tool("stop_run", "Stop the run in progress.", props()))
                .put(tool("list_runs", "Recent runs, newest first.",
                        props().put("limit", prop("integer", "How many (1-50, default 10)."))))
                .put(tool("get_step_screenshot", "The screenshot the agent saw at one step of a run.",
                        props().put("run_id", prop("string", "Run id.")).put("step", prop("integer", "Step number, from 1.")),
                        "run_id", "step"))
                .put(tool("list_skills", "Saved skills (tasks the phone replays without model calls).", props()))
                .put(tool("observe", "The current screen: foreground app, numbered UI elements with bounds on a 0-1000 "
                                + "scale, and a screenshot. Action tools take an element index from the latest observe call.",
                        props().put("screenshot", prop("boolean", "Attach a screenshot (default true)."))));
        // The phone actions, with the same definitions the phone's own agent gets.
        JSONArray agentTools = FlashLoop.tools(false);
        for (int i = 0; i < agentTools.length(); i++) {
            JSONObject f = agentTools.getJSONObject(i).getJSONObject("function");
            if ("finish".equals(f.getString("name"))) continue;
            JSONObject schema = new JSONObject(f.getJSONObject("parameters").toString());
            JSONObject index = schema.getJSONObject("properties").optJSONObject("index");
            if (index != null) index.put("description", "Index of the element in the latest observe result.");
            out.put(new JSONObject().put("name", f.getString("name")).put("description", f.getString("description"))
                    .put("inputSchema", schema));
        }
        return out;
    }

    private static JSONObject tool(String name, String description, JSONObject props, String... required) throws Exception {
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        return new JSONObject().put("name", name).put("description", description)
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", props).put("required", req));
    }

    private static JSONObject props() {
        return new JSONObject();
    }

    private static JSONObject prop(String type, String description) throws Exception {
        return new JSONObject().put("type", type).put("description", description);
    }

    // ------------------------------------------------------------------ call

    JSONObject call(String name, JSONObject args) throws Exception {
        if (args == null) args = new JSONObject();
        try {
            switch (name) {
                case "run_task": return runTask(args);
                case "get_run": return getRun(args);
                case "stop_run": return stopRun();
                case "list_runs": return listRuns(args);
                case "get_step_screenshot": return stepScreenshot(args);
                case "list_skills": return listSkills();
                case "observe": synchronized (screen) { return observe(args); }
                default: synchronized (screen) { return act(name, args); }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    private JSONObject runTask(JSONObject args) throws Exception {
        String task = args.optString("task").trim();
        if (task.isEmpty()) throw new IllegalArgumentException("task is empty");
        if (AgentAccessibilityService.get() == null) throw new IllegalStateException("the accessibility service is off on the phone");
        if (ProviderStore.getActive(ctx) == null) throw new IllegalStateException("no model is configured on the phone");
        RunRecord live = RunState.current();
        if (RunState.isRunning()) throw new IllegalStateException("a run is already in progress: " + live.id);
        String id = AgentService.start(ctx, task);
        int wait = Math.max(0, Math.min(MAX_WAIT_S, args.optInt("wait_seconds", 0)));
        long deadline = System.currentTimeMillis() + wait * 1000L;
        RunRecord r = null;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            r = find(id);
            if (r != null && !r.isRunning()) break;
        }
        if (r == null) r = find(id);
        JSONObject out = r == null ? new JSONObject().put("run_id", id).put("status", RunRecord.RUNNING) : summary(r, false);
        return text(out.toString(2));
    }

    private JSONObject getRun(JSONObject args) throws Exception {
        RunRecord r = find(runId(args));
        if (r == null) throw new IllegalArgumentException("no such run");
        return text(summary(r, args.optBoolean("include_steps", true)).toString(2));
    }

    private JSONObject stopRun() throws Exception {
        if (!RunState.isRunning()) return text("No run is in progress.");
        AgentService.stop(ctx);
        return text("Stopping run " + RunState.current().id + ".");
    }

    private JSONObject listRuns(JSONObject args) throws Exception {
        int limit = Math.max(1, Math.min(50, args.optInt("limit", 10)));
        JSONArray out = new JSONArray();
        List<RunRecord> runs = RunStore.list(ctx);
        for (int i = 0; i < runs.size() && i < limit; i++) out.put(summary(runs.get(i), false));
        return text(out.toString(2));
    }

    private JSONObject stepScreenshot(JSONObject args) throws Exception {
        String id = runId(args);
        byte[] jpeg = RunStore.screenshotBytes(ctx, id, args.optInt("step"));
        if (jpeg == null) throw new IllegalArgumentException("no screenshot for that step");
        return new JSONObject().put("content", new JSONArray().put(image(jpeg)));
    }

    private JSONObject listSkills() throws Exception {
        JSONArray out = new JSONArray();
        for (Skill s : SkillStore.list(ctx)) {
            out.put(new JSONObject().put("name", s.name).put("task", s.task).put("enabled", s.enabled)
                    .put("steps", s.actions.size()).put("used", s.used).put("succeeded", s.succeeded));
        }
        return text(out.toString(2));
    }

    private JSONObject observe(JSONObject args) throws Exception {
        AgentAccessibilityService s = service();
        Observation obs = Observation.capture(s, args.optBoolean("screenshot", true));
        if (FlashLoop.BLOCKED_APPS.contains(obs.packageName)) {
            lastObservation = null;
            throw new IllegalStateException("a blocked app (account ban risk) is in front; nothing is read from it");
        }
        lastObservation = obs;
        String head = "Foreground app: " + (obs.packageName.isEmpty() ? "unknown" : obs.packageName)
                + "\nScreen: " + obs.screenW + "x" + obs.screenH + " px; bounds and x,y use a 0-1000 scale.\n"
                + "--- Visible UI Elements ---\n" + obs.elementsText();
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", head));
        if (obs.jpeg != null) content.put(image(obs.jpeg));
        return new JSONObject().put("content", content);
    }

    private JSONObject act(String name, JSONObject args) throws Exception {
        if (name.equals("finish") || !isActionTool(name)) throw new IllegalArgumentException("unknown tool: " + name);
        if (RunState.isRunning()) throw new IllegalStateException("the phone's agent is running a task; stop_run first");
        AgentAccessibilityService s = service();
        if (FlashLoop.BLOCKED_APPS.contains(s.currentPackage())) {
            throw new IllegalStateException("a blocked app (account ban risk) is in front");
        }
        // Our own screens hold the MCP settings and the token: only leave them, never operate them.
        if (ctx.getPackageName().equals(s.currentPackage()) && !SCREEN_INDEPENDENT.contains(name)) {
            throw new IllegalStateException("Artemis's own screen is in front; call home or open_app first");
        }
        Observation obs = lastObservation;
        if (args.has("index") && obs == null) throw new IllegalArgumentException("call observe first; index refers to its element list");
        if (obs == null) obs = Observation.capture(s, false);
        String outcome = Actions.execute(s, obs, new ChatClient.ToolCall(name, args));
        // Indices belong to the observation they came from; the screen has moved on.
        lastObservation = null;
        return outcome.contains("FAILED") ? error(outcome) : text(outcome);
    }

    private static boolean isActionTool(String name) throws Exception {
        JSONArray t = FlashLoop.tools(false);
        for (int i = 0; i < t.length(); i++) {
            if (t.getJSONObject(i).getJSONObject("function").getString("name").equals(name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static AgentAccessibilityService service() {
        AgentAccessibilityService s = AgentAccessibilityService.get();
        if (s == null) throw new IllegalStateException("the accessibility service is off on the phone");
        return s;
    }

    private static String runId(JSONObject args) {
        String id = args.optString("run_id");
        if (!id.matches(RUN_ID)) throw new IllegalArgumentException("not a run id: " + FlashLoop.clip(id, 40));
        return id;
    }

    private RunRecord find(String id) {
        RunRecord live = RunState.current();
        if (live != null && live.id.equals(id)) return live;
        return RunStore.load(ctx, id);
    }

    private static JSONObject summary(RunRecord r, boolean steps) throws Exception {
        JSONObject o = new JSONObject().put("run_id", r.id).put("task", r.task).put("status", r.status)
                .put("answer", r.answer).put("model", r.model).put("steps", r.steps.size())
                .put("replayed_steps", r.replayedSteps()).put("duration_s", Math.round(r.durationMs() / 100.0) / 10.0)
                .put("tokens_in", r.promptTokens()).put("tokens_out", r.completionTokens());
        if (!r.skillId.isEmpty()) o.put("skill", r.skillId);
        if (!steps) return o;
        JSONArray list = new JSONArray();
        for (RunRecord.Step s : r.steps) {
            JSONArray actions = new JSONArray();
            for (String a : s.actions) actions.put(a);
            list.put(new JSONObject().put("step", s.index).put("app", s.app).put("replayed", s.replayed)
                    .put("reasoning", FlashLoop.clip(s.reasoning, STEP_TEXT_MAX)).put("actions", actions)
                    .put("ms", s.totalMs()).put("has_screenshot", s.hasScreenshot));
        }
        return o.put("step_list", list);
    }

    private static JSONObject text(String s) throws Exception {
        return new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", s)));
    }

    private static JSONObject error(String message) throws Exception {
        return text(message).put("isError", true);
    }

    private static JSONObject image(byte[] jpeg) throws Exception {
        return new JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP));
    }
}
