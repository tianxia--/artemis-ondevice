package com.artemis.ondevice;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Lessons in app-private {@code experience.json}; never leaves the phone. */
final class ExperienceStore {

    /** Principles and patterns put into one run's prompt, and facts per foreground app. */
    static final int MAX_GENERAL = 6;
    static final int MAX_FACTS_PER_APP = 3;
    /** A lesson the user did not approve is demoted when its runs keep failing. */
    static final int DEMOTE_AFTER_USES = 5;
    static final int DEMOTE_BELOW_PERCENT = 40;
    static final int MAX_LESSONS = 200;

    private ExperienceStore() {}

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "experience.json");
    }

    static synchronized List<Experience> list(Context ctx) {
        List<Experience> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.exists()) return out;
        try {
            JSONArray a = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    .optJSONArray("lessons");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(Experience.fromJson(a.getJSONObject(i)));
        } catch (Exception e) {
            Log.w(AgentService.TAG, "experience not readable: " + e.getMessage());
        }
        return out;
    }

    private static void write(Context ctx, List<Experience> lessons) throws Exception {
        JSONArray a = new JSONArray();
        for (Experience e : lessons) a.put(e.toJson());
        File tmp = new File(ctx.getFilesDir(), "experience.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(new JSONObject().put("lessons", a).toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file(ctx))) throw new IllegalStateException("Cannot write experience");
    }

    /** Higher first: drawn from more runs, and runs that used it went well. */
    private static int score(Experience e) {
        return e.seen * 2 + e.successes - 2 * (e.uses - e.successes) + (e.approved ? 3 : 0);
    }

    private static void rank(List<Experience> list) {
        Collections.sort(list, (a, b) -> Integer.compare(score(b), score(a)));
    }

    /** Active principles and patterns for a run's prompt, best first. */
    static List<Experience> general(Context ctx) {
        List<Experience> out = new ArrayList<>();
        for (Experience e : list(ctx)) if (e.active() && !Experience.FACT.equals(e.layer)) out.add(e);
        rank(out);
        return out.size() > MAX_GENERAL ? new ArrayList<>(out.subList(0, MAX_GENERAL)) : out;
    }

    /** Active facts about one app, best first. */
    static List<Experience> facts(List<Experience> all, String pkg) {
        List<Experience> out = new ArrayList<>();
        for (Experience e : all) if (e.active() && Experience.FACT.equals(e.layer) && e.app.equals(pkg)) out.add(e);
        rank(out);
        return out.size() > MAX_FACTS_PER_APP ? new ArrayList<>(out.subList(0, MAX_FACTS_PER_APP)) : out;
    }

    /**
     * Applies one reflection: lessons the model matched to an existing one ({@code same_as}) count
     * one more sighting; the rest are added as candidates. Returns {new, confirmed}.
     */
    static synchronized int[] addFromReflection(Context ctx, List<JSONObject> lessons) throws Exception {
        List<Experience> all = list(ctx);
        long now = System.currentTimeMillis();
        int added = 0, confirmed = 0;
        for (JSONObject l : lessons) {
            Experience same = null;
            String sameId = l.optString("same_as", "");
            for (Experience e : all) if (!sameId.isEmpty() && e.id.equals(sameId)) same = e;
            if (same != null) {
                same.seen++;
                same.updatedAt = now;
                confirmed++;
                continue;
            }
            String text = l.optString("text").trim();
            if (text.isEmpty()) continue;
            Experience e = new Experience();
            e.id = UUID.randomUUID().toString().substring(0, 8);
            String layer = l.optString("layer");
            e.layer = Experience.FACT.equals(layer) || Experience.PATTERN.equals(layer) ? layer : Experience.PRINCIPLE;
            e.app = Experience.FACT.equals(e.layer) ? l.optString("app").trim() : "";
            if (Experience.FACT.equals(e.layer) && e.app.isEmpty()) e.layer = Experience.PATTERN;
            e.text = FlashLoop.clip(text, 240);
            e.when = FlashLoop.clip(l.optString("when").trim(), 120);
            e.createdAt = now;
            e.updatedAt = now;
            all.add(e);
            added++;
        }
        // Keep the store bounded: drop the weakest candidates first.
        if (all.size() > MAX_LESSONS) {
            List<Experience> candidates = new ArrayList<>();
            for (Experience e : all) if (!e.active() && !e.approved) candidates.add(e);
            rank(candidates);
            for (int i = candidates.size() - 1; i >= 0 && all.size() > MAX_LESSONS; i--) all.remove(candidates.get(i));
        }
        write(ctx, all);
        return new int[]{added, confirmed};
    }

    /** Counts a run that had these lessons in its prompt; demotes unapproved lessons that keep failing. */
    static synchronized void recordUse(Context ctx, List<String> ids, boolean completed, int steps) {
        if (ids.isEmpty()) return;
        try {
            List<Experience> all = list(ctx);
            for (Experience e : all) {
                if (!ids.contains(e.id)) continue;
                e.uses++;
                if (completed) e.successes++;
                e.steps += steps;
                if (!e.approved && e.uses >= DEMOTE_AFTER_USES && e.successRate() < DEMOTE_BELOW_PERCENT) {
                    Log.i(AgentService.TAG, "lesson " + e.id + " demoted: " + e.successRate() + "% of " + e.uses + " runs");
                    e.seen = 1;
                    e.uses = 0;
                    e.successes = 0;
                    e.steps = 0;
                }
            }
            write(ctx, all);
        } catch (Exception e) {
            Log.w(AgentService.TAG, "lesson use not saved: " + e.getMessage());
        }
    }

    static synchronized void update(Context ctx, Experience changed) throws Exception {
        List<Experience> all = list(ctx);
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(changed.id)) all.set(i, changed);
        write(ctx, all);
    }

    static synchronized void remove(Context ctx, String id) throws Exception {
        List<Experience> all = list(ctx);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(id)) all.remove(i);
        write(ctx, all);
    }
}
