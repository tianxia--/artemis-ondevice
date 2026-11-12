package com.artemis.ondevice;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * After a run: one extra model call reviews where steps were wasted or the run went wrong, and
 * distills lessons for later runs into {@link ExperienceStore}. Lessons that restate an existing
 * one only count as one more sighting, so a lesson must recur before it is used (see
 * {@link Experience#active}). Personal data is kept out by instruction; lessons stay on the phone.
 */
final class Reflector {

    static final int MAX_LESSONS_PER_RUN = 4;
    private static final int MIN_STEPS = 3;
    private static final int MAX_EXISTING = 60;
    private static final int MAX_TOKENS = 4000;

    private Reflector() {}

    /** Completed or failed runs where the model did the work; stopped, errored and pure replays teach little. */
    static boolean worthReviewing(RunRecord r) {
        boolean ended = RunRecord.COMPLETED.equals(r.status) || RunRecord.FAILED.equals(r.status);
        return ended && r.steps.size() - r.replayedSteps() >= MIN_STEPS;
    }

    /** Reviews the run and stores its lessons; returns a short summary for the run record. */
    static String review(Context ctx, ChatClient client, RunRecord run) throws Exception {
        List<Experience> existing = ExperienceStore.list(ctx);
        // Reasoning models spend part of the budget thinking before the JSON; leave them room.
        // Answered through a tool call: with free text, a reasoning model can think through the whole
        // token budget and never write the JSON (seen with a DeepSeek model: 0 chars, stop "length").
        ChatClient.Result r = client.complete(run.model, system(), user(ctx, run, existing), null, tools(), MAX_TOKENS);
        List<JSONObject> lessons = fromToolCall(r);
        if (lessons.isEmpty()) lessons = parse(r.text);
        boolean answered = !r.toolCalls.isEmpty() || (r.text.contains("{") && r.text.contains("}"));
        if (lessons.isEmpty()) {
            return answered ? "nothing to learn"
                    : "no usable reply (" + r.text.length() + " chars, stop: " + r.finishReason + ")";
        }
        int[] n = ExperienceStore.addFromReflection(ctx, lessons);
        return "added " + n[0] + ", confirmed " + n[1];
    }

    private static String system() {
        return "You review a finished run of an Android phone agent and distill lessons that will make LATER, "
                + "DIFFERENT tasks faster and more reliable. Look for steps that were wasted (repeated searches, "
                + "dead ends tried twice, going back to re-check finished parts, wrong taps) and for what avoided "
                + "trouble. For each, ask: what way of thinking would have avoided it, and does it hold beyond this task?\n\n"
                + "Three layers, most valuable first:\n"
                + "- principle: a way of thinking for any task (e.g. \"after two searches with no results, stop "
                + "searching and browse the menus instead\");\n"
                + "- pattern: how to handle a kind of screen or control in any app (time pickers, search boxes, "
                + "permission dialogs, lists that reorder);\n"
                + "- fact: how one app on this phone is laid out (needs `app`, the package name), e.g. where a "
                + "setting lives or which search terms work.\n\n"
                + "Rules:\n"
                + "- At most " + MAX_LESSONS_PER_RUN + " lessons, each tied to steps of this run. Fewer, sharper lessons "
                + "beat many vague ones; return an empty list when there is nothing worth keeping.\n"
                + "- Confirm existing lessons with {\"same_as\": \"<its id>\"}: when a lesson of yours restates one, and "
                + "also when this run followed an existing lesson and it worked, or ran into the problem a lesson describes. "
                + "Confirmations are how good lessons get used, so give them even when nothing new was learned.\n"
                + "- Write lessons as general advice in English, imperative, one sentence; `when` names the situation.\n"
                + "- Never include personal data: no names of people or contacts, message or chat content, phone "
                + "numbers, email or postal addresses, account names, or anything typed into the phone.\n"
                + "- Never turn the user's own decisions into advice about the task (no \"avoid X\" or \"use another "
                + "path\" because the user declined X): a declined confirmation or a chosen option is a choice for that "
                + "run. How to handle a decline in general (stop, report it, never route around it) is a fine lesson.\n"
                + "- Do not restate the agent's built-in rules (use the task memory, translate searches into the "
                + "screen's language, re-observe after a change).\n\n"
                + "Think briefly, then call `record_lessons` once (with an empty list when there is nothing to keep). "
                + "Each item is either a new lesson {layer, app (facts only), when, text, evidence} or {same_as: \"<id>\"}.";
    }

    private static String user(Context ctx, RunRecord run, List<Experience> existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(run.task).append('\n')
                .append("Outcome: ").append(run.status).append(" after ").append(run.steps.size()).append(" steps")
                .append(run.replayedSteps() > 0 ? " (" + run.replayedSteps() + " replayed from a skill)" : "").append('\n')
                .append("Final answer: ").append(FlashLoop.clip(run.answer, 300)).append("\n\n")
                .append(run.memory.render()).append('\n');
        RunRecord before = earlierRun(ctx, run);
        if (before != null) {
            sb.append("# AN EARLIER RUN OF THE SAME TASK: ").append(before.status).append(" after ")
                    .append(before.steps.size()).append(" steps. Where the two runs differ is often the lesson.\n")
                    .append(before.memory.render()).append('\n');
        }
        sb.append("# STEPS\n");
        for (RunRecord.Step s : run.steps) {
            sb.append('#').append(s.index).append(" [").append(s.app).append(']')
                    .append(s.replayed ? " (replayed)" : "").append(s.failedAction ? " (FAILED/SKIPPED)" : "").append(' ')
                    .append(FlashLoop.clip(s.reasoning, 160)).append(" -> ");
            List<String> acts = new ArrayList<>();
            for (String a : s.actions) acts.add(FlashLoop.clip(a, 90));
            sb.append(String.join(" ; ", acts)).append('\n');
        }
        sb.append("\n# EXISTING LESSONS\n");
        int n = 0;
        for (Experience e : existing) {
            if (e.off || n++ >= MAX_EXISTING) continue;
            sb.append(e.id).append(" [").append(e.layer).append(e.app.isEmpty() ? "" : " " + e.app).append("] ")
                    .append(e.promptLine()).append('\n');
        }
        if (n == 0) sb.append("(none yet)\n");
        return sb.toString();
    }

    private static RunRecord earlierRun(Context ctx, RunRecord run) {
        String task = Skill.normalize(run.task);
        for (RunRecord r : RunStore.list(ctx)) {
            if (!r.id.equals(run.id) && Skill.normalize(r.task).equals(task) && !r.isRunning()) return r;
        }
        return null;
    }

    static final String TOOL = "record_lessons";

    private static JSONArray tools() {
        try {
            JSONObject item = new JSONObject().put("type", "object").put("properties", new JSONObject()
                    .put("same_as", new JSONObject().put("type", "string").put("description", "Id of an existing lesson this restates."))
                    .put("layer", new JSONObject().put("type", "string")
                            .put("enum", new JSONArray().put(Experience.PRINCIPLE).put(Experience.PATTERN).put(Experience.FACT)))
                    .put("app", new JSONObject().put("type", "string").put("description", "Package name; facts only."))
                    .put("when", new JSONObject().put("type", "string").put("description", "The situation it applies to."))
                    .put("text", new JSONObject().put("type", "string").put("description", "The lesson, one imperative sentence."))
                    .put("evidence", new JSONObject().put("type", "string").put("description", "Steps of this run, e.g. \"steps 4-9\".")));
            JSONObject params = new JSONObject().put("type", "object").put("properties", new JSONObject()
                    .put("lessons", new JSONObject().put("type", "array").put("items", item)))
                    .put("required", new JSONArray().put("lessons"));
            return new JSONArray().put(new JSONObject().put("type", "function").put("function", new JSONObject()
                    .put("name", TOOL).put("description", "Record the lessons of this run (at most " + MAX_LESSONS_PER_RUN + ").")
                    .put("parameters", params)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<JSONObject> fromToolCall(ChatClient.Result r) {
        List<JSONObject> out = new ArrayList<>();
        for (ChatClient.ToolCall c : r.toolCalls) {
            if (!TOOL.equals(c.name)) continue;
            JSONArray l = c.args.optJSONArray("lessons");
            for (int i = 0; l != null && i < l.length() && out.size() < MAX_LESSONS_PER_RUN; i++) {
                JSONObject o = l.optJSONObject(i);
                if (o != null) out.add(o);
            }
        }
        return out;
    }

    /** The lessons in a plain-text reply; tolerates text around the JSON object. */
    static List<JSONObject> parse(String text) {
        List<JSONObject> out = new ArrayList<>();
        if (text == null) return out;
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) return out;
        try {
            JSONArray l = new JSONObject(text.substring(a, b + 1)).optJSONArray("lessons");
            for (int i = 0; l != null && i < l.length() && out.size() < MAX_LESSONS_PER_RUN; i++) {
                JSONObject o = l.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Exception e) {
            Log.w(AgentService.TAG, "reflection reply not JSON");
        }
        return out;
    }
}
