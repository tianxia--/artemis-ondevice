package com.artemis.ondevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * A saved skill: the actions of a successful run, replayed without model calls the next time
 * the same task is asked (see {@link FlashLoop}). Each action is stored in replayable form:
 *
 * <pre>
 * {"tool":"tap", "app":"com.android.settings",
 *  "key":"Text: 'Network & internet'",   // element to find again (absent for x,y taps)
 *  "nx":500, "ny":312,                    // where it was, 0-1000; tie-breaker, or the point itself
 *  "args":{...}}                          // the tool's other arguments (text, direction, ms ...)
 * </pre>
 */
final class Skill {

    String id = "";
    String name = "";
    String task = "";
    boolean enabled = true;
    long createdAt;
    String sourceRun = "";
    int used, succeeded;
    final List<JSONObject> actions = new ArrayList<>();

    /** Element not on screen. */
    static final int NOT_FOUND = -1;
    /** The action has no target element (back, home, open_app, wait, x,y taps ...). */
    static final int NO_ELEMENT = -2;

    /** Task text compared for matching: case, spaces and punctuation ignored. */
    static String normalize(String task) {
        return task == null ? "" : task.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    /**
     * Element label without what changes between runs of the same screen: check state, state
     * descriptions and digits (clocks, battery levels, counters).
     */
    static String key(String label) {
        return label.replaceAll("\\b(un)?checked\\b|state=[^,|]*", "").replaceAll("\\d", "")
                .replaceAll("[,\\s|]+$", "").trim();
    }

    /** The successful tool call in replayable form, or null when it is not replayable. */
    static JSONObject action(Observation obs, ChatClient.ToolCall call, String outcome) {
        try {
            JSONObject args = new JSONObject();
            for (Iterator<String> it = call.args.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!k.equals("index") && !k.equals("x") && !k.equals("y") && !k.equals("target_description")) {
                    args.put(k, call.args.get(k));
                }
            }
            if ("open_app".equals(call.name)) {
                // Replay the package that was actually launched, not the label the model guessed.
                int arrow = outcome.indexOf("-> ");
                if (arrow >= 0) args.put("app", outcome.substring(arrow + 3).split(" ")[0]);
            }
            JSONObject a = new JSONObject().put("tool", call.name).put("app", obs.packageName).put("args", args);
            JSONObject c = call.args;
            if (c.has("index") && !c.isNull("index")) {
                int i = c.optInt("index", -1);
                if (i < 0 || i >= obs.elements.size()) return null;
                Observation.Element e = obs.elements.get(i);
                a.put("key", key(e.label)).put("nx", obs.nx(e.cx())).put("ny", obs.ny(e.cy()));
            } else if (c.has("x") && c.has("y")) {
                a.put("nx", c.optInt("x")).put("ny", c.optInt("y"));
            }
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the action must run in the app it was recorded in (it aims at the screen). */
    static boolean needsApp(JSONObject a) {
        return a.has("nx") || "type_text".equals(a.optString("tool"));
    }

    /**
     * Index of the element to act on: same key, nearest to where it was recorded.
     * {@link #NO_ELEMENT} when the action has no element, {@link #NOT_FOUND} when it is missing.
     */
    static int find(Observation obs, JSONObject a) {
        String k = a.optString("key", "");
        if (k.isEmpty()) return NO_ELEMENT;
        int best = NOT_FOUND;
        long bestDist = Long.MAX_VALUE;
        int nx = a.optInt("nx"), ny = a.optInt("ny");
        for (int i = 0; i < obs.elements.size(); i++) {
            Observation.Element e = obs.elements.get(i);
            if (!k.equals(key(e.label))) continue;
            long dx = obs.nx(e.cx()) - nx, dy = obs.ny(e.cy()) - ny;
            long dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /** The recorded action as a tool call against the current screen. */
    static ChatClient.ToolCall toCall(JSONObject a, int index) throws Exception {
        JSONObject args = new JSONObject(a.optJSONObject("args") == null ? "{}" : a.optJSONObject("args").toString());
        if (index >= 0) {
            args.put("index", index);
        } else if (a.has("nx") && !a.has("key")) {
            args.put("x", a.optInt("nx")).put("y", a.optInt("ny"));
        }
        return new ChatClient.ToolCall(a.optString("tool"), args);
    }

    JSONObject toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject o : actions) a.put(o);
        return new JSONObject().put("id", id).put("name", name).put("task", task).put("enabled", enabled)
                .put("created_at", createdAt).put("source_run", sourceRun)
                .put("used", used).put("succeeded", succeeded).put("actions", a);
    }

    static Skill fromJson(JSONObject o) {
        Skill s = new Skill();
        s.id = o.optString("id");
        s.name = o.optString("name");
        s.task = o.optString("task");
        s.enabled = o.optBoolean("enabled", true);
        s.createdAt = o.optLong("created_at");
        s.sourceRun = o.optString("source_run");
        s.used = o.optInt("used");
        s.succeeded = o.optInt("succeeded");
        JSONArray a = o.optJSONArray("actions");
        if (a != null) for (int i = 0; i < a.length(); i++) s.actions.add(a.optJSONObject(i));
        return s;
    }
}
