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
import java.util.List;
import java.util.UUID;

/** Saved skills in app-private {@code skills.json}: {@code {"skills":[...]}}, newest first. */
final class SkillStore {

    private SkillStore() {}

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "skills.json");
    }

    static synchronized List<Skill> list(Context ctx) {
        List<Skill> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.exists()) return out;
        try {
            JSONArray a = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    .optJSONArray("skills");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(Skill.fromJson(a.getJSONObject(i)));
        } catch (Exception e) {
            Log.w(AgentService.TAG, "skills not readable: " + e.getMessage());
        }
        return out;
    }

    private static void write(Context ctx, List<Skill> skills) throws Exception {
        JSONArray a = new JSONArray();
        for (Skill s : skills) a.put(s.toJson());
        File tmp = new File(ctx.getFilesDir(), "skills.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(new JSONObject().put("skills", a).toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file(ctx))) throw new IllegalStateException("Cannot write skills");
    }

    /** The enabled skill for this task text, or null. */
    static Skill find(Context ctx, String task) {
        String t = Skill.normalize(task);
        if (t.isEmpty()) return null;
        for (Skill s : list(ctx)) if (s.enabled && Skill.normalize(s.task).equals(t)) return s;
        return null;
    }

    /** A completed run with replayable actions can become a skill. */
    static boolean canSave(RunRecord r) {
        return r != null && RunRecord.COMPLETED.equals(r.status) && r.hasReplayableActions();
    }

    /** Saves the run's actions as the skill for its task, replacing an existing one for the same task. */
    static synchronized Skill saveFromRun(Context ctx, RunRecord r) throws Exception {
        Skill s = new Skill();
        s.id = UUID.randomUUID().toString().substring(0, 8);
        s.name = r.task;
        s.task = r.task;
        s.createdAt = System.currentTimeMillis();
        s.sourceRun = r.id;
        for (RunRecord.Step step : r.steps) s.actions.addAll(step.replay);
        if (s.actions.isEmpty()) throw new IllegalArgumentException("run has no replayable actions");
        List<Skill> skills = list(ctx);
        String t = Skill.normalize(r.task);
        for (int i = skills.size() - 1; i >= 0; i--) {
            if (Skill.normalize(skills.get(i).task).equals(t)) skills.remove(i);
        }
        skills.add(0, s);
        write(ctx, skills);
        return s;
    }

    static synchronized void update(Context ctx, Skill changed) throws Exception {
        List<Skill> skills = list(ctx);
        for (int i = 0; i < skills.size(); i++) {
            if (skills.get(i).id.equals(changed.id)) skills.set(i, changed);
        }
        write(ctx, skills);
    }

    static synchronized void remove(Context ctx, String id) throws Exception {
        List<Skill> skills = list(ctx);
        for (int i = skills.size() - 1; i >= 0; i--) if (skills.get(i).id.equals(id)) skills.remove(i);
        write(ctx, skills);
    }

    /** Counts a use; success means every recorded step replayed and the run completed. */
    static synchronized void recordOutcome(Context ctx, String id, boolean success) {
        try {
            List<Skill> skills = list(ctx);
            for (Skill s : skills) {
                if (!s.id.equals(id)) continue;
                s.used++;
                if (success) s.succeeded++;
            }
            write(ctx, skills);
        } catch (Exception e) {
            Log.w(AgentService.TAG, "skill outcome not saved: " + e.getMessage());
        }
    }
}
