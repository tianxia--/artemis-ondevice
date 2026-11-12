package com.artemis.ondevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The run's working memory, written by the model through the {@code update_memory} tool and
 * shown at the top of every prompt. Unlike the step history (last {@link FlashLoop#HISTORY_STEPS}
 * steps only) it lasts the whole run, so finished parts, facts found and dead ends are not
 * forgotten when the steps that produced them scroll out of the history.
 */
final class TaskMemory {

    static final int MAX_ITEMS = 12;
    static final int MAX_ITEM_CHARS = 160;

    final List<String> completed = new ArrayList<>();
    final List<String> facts = new ArrayList<>();
    final List<String> deadEnds = new ArrayList<>();
    String next = "";

    boolean isEmpty() {
        return completed.isEmpty() && facts.isEmpty() && deadEnds.isEmpty() && next.isEmpty();
    }

    /** Replaces the memory with the tool call's snapshot; lists left out keep their content. */
    void update(JSONObject args) {
        replace(completed, args.optJSONArray("completed"));
        replace(facts, args.optJSONArray("facts"));
        replace(deadEnds, args.optJSONArray("dead_ends"));
        if (args.has("next")) next = FlashLoop.clip(args.optString("next").trim(), MAX_ITEM_CHARS);
    }

    private static void replace(List<String> list, JSONArray items) {
        if (items == null) return;
        list.clear();
        for (int i = 0; i < items.length() && list.size() < MAX_ITEMS; i++) {
            String s = items.optString(i).trim();
            if (!s.isEmpty()) list.add(FlashLoop.clip(s, MAX_ITEM_CHARS));
        }
    }

    /** Prompt section; the empty memory says how to start one. */
    String render() {
        StringBuilder sb = new StringBuilder("# TASK MEMORY (kept for the whole task; replace it with update_memory)\n");
        if (isEmpty()) return sb.append("(empty)\n").toString();
        section(sb, "Completed parts", completed);
        section(sb, "Facts found", facts);
        section(sb, "Dead ends (do not try again)", deadEnds);
        if (!next.isEmpty()) sb.append("Next: ").append(next).append('\n');
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, List<String> items) {
        if (items.isEmpty()) return;
        sb.append(title).append(":\n");
        for (String s : items) sb.append("- ").append(s).append('\n');
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("completed", new JSONArray(completed)).put("facts", new JSONArray(facts))
                .put("dead_ends", new JSONArray(deadEnds)).put("next", next);
    }

    static TaskMemory fromJson(JSONObject o) {
        TaskMemory m = new TaskMemory();
        if (o != null) m.update(o);
        return m;
    }
}
