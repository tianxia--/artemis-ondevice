package com.artemis.ondevice;

import org.json.JSONObject;

/**
 * One lesson distilled from finished runs by {@link Reflector}. Three layers:
 * <ul>
 *   <li>{@link #PRINCIPLE}: a way of thinking that holds for any task;</li>
 *   <li>{@link #PATTERN}: how to handle a kind of screen or control, in any app;</li>
 *   <li>{@link #FACT}: something about one app on this phone ({@link #app} set).</li>
 * </ul>
 * A lesson starts as a candidate and reaches the prompt once it was drawn from
 * {@link #ACTIVATE_AT} runs or the user approved it; it drops back when the runs that used it
 * keep failing (see {@link ExperienceStore#recordUse}).
 */
final class Experience {

    static final String PRINCIPLE = "principle";
    static final String PATTERN = "pattern";
    static final String FACT = "fact";

    /** Runs a lesson must be drawn from before it is used without the user's approval. */
    static final int ACTIVATE_AT = 2;

    String id = "";
    String layer = PRINCIPLE;
    /** Package the lesson is about (facts), or "" for any app. */
    String app = "";
    String text = "";
    /** When it applies, e.g. "a Settings search finds nothing". */
    String when = "";
    /** Runs it was drawn from. */
    int seen = 1;
    boolean approved;
    /** Turned off by the user or by poor results; never used while off. */
    boolean off;
    /** Runs that had it in the prompt, how many of them completed, and their total steps. */
    int uses, successes, steps;
    long createdAt, updatedAt;

    boolean active() {
        return !off && (approved || seen >= ACTIVATE_AT);
    }

    int successRate() {
        return uses == 0 ? -1 : Math.round(100f * successes / uses);
    }

    String promptLine() {
        return (when.isEmpty() ? "" : "When " + when + ": ") + text;
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("id", id).put("layer", layer).put("app", app).put("text", text).put("when", when)
                .put("seen", seen).put("approved", approved).put("off", off).put("uses", uses)
                .put("successes", successes).put("steps", steps).put("created_at", createdAt).put("updated_at", updatedAt);
    }

    static Experience fromJson(JSONObject o) {
        Experience e = new Experience();
        e.id = o.optString("id");
        e.layer = o.optString("layer", PRINCIPLE);
        e.app = o.optString("app");
        e.text = o.optString("text");
        e.when = o.optString("when");
        e.seen = o.optInt("seen", 1);
        e.approved = o.optBoolean("approved");
        e.off = o.optBoolean("off");
        e.uses = o.optInt("uses");
        e.successes = o.optInt("successes");
        e.steps = o.optInt("steps");
        e.createdAt = o.optLong("created_at");
        e.updatedAt = o.optLong("updated_at");
        return e;
    }
}
