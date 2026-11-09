package com.artemis.ondevice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Run history on app-private storage: {@code runs/<id>/run.json} plus one JPEG per step
 * ({@code step_<n>.jpg}). Keeps the newest {@link #KEEP} runs. Disk I/O: call off the main
 * thread (the activities use a background executor).
 */
final class RunStore {

    static final int KEEP = 100;

    private RunStore() {}

    private static File root(Context ctx) {
        File d = new File(ctx.getFilesDir(), "runs");
        if (!d.exists() && !d.mkdirs()) throw new IllegalStateException("Cannot create " + d);
        return d;
    }

    private static File dir(Context ctx, String id) {
        File d = new File(root(ctx), id);
        if (!d.exists() && !d.mkdirs()) throw new IllegalStateException("Cannot create " + d);
        return d;
    }

    static synchronized void save(Context ctx, RunRecord r) throws Exception {
        File d = dir(ctx, r.id);
        File tmp = new File(d, "run.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(r.toJson().toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(d, "run.json"))) throw new IllegalStateException("Cannot write run " + r.id);
    }

    static void saveScreenshot(Context ctx, String id, int step, byte[] jpeg) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(dir(ctx, id), "step_" + step + ".jpg"))) {
            out.write(jpeg);
        }
    }

    /** The step's JPEG as stored, or null. */
    static byte[] screenshotBytes(Context ctx, String id, int step) {
        File f = new File(new File(root(ctx), id), "step_" + step + ".jpg");
        try {
            return f.exists() ? Files.readAllBytes(f.toPath()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static Bitmap loadScreenshot(Context ctx, String id, int step, int maxSide) {
        File f = new File(new File(root(ctx), id), "step_" + step + ".jpg");
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    static RunRecord load(Context ctx, String id) {
        File f = new File(new File(root(ctx), id), "run.json");
        if (!f.exists()) return null;
        try {
            RunRecord live = RunState.current();
            if (live != null && live.id.equals(id) && live.isRunning()) return live;
            return RunRecord.fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            android.util.Log.w(AgentService.TAG, "Unreadable run " + id + ": " + e.getMessage());
            return null;
        }
    }

    /** Newest first. Unreadable runs are skipped (and logged by {@link #load}). */
    static List<RunRecord> list(Context ctx) {
        List<RunRecord> out = new ArrayList<>();
        for (File d : sortedDirs(ctx)) {
            RunRecord r = load(ctx, d.getName());
            if (r != null) out.add(r);
        }
        return out;
    }

    static void delete(Context ctx, String id) {
        deleteDir(new File(root(ctx), id));
    }

    static void clear(Context ctx) {
        for (File d : sortedDirs(ctx)) deleteDir(d);
    }

    static void prune(Context ctx) {
        List<File> dirs = sortedDirs(ctx);
        for (int i = KEEP; i < dirs.size(); i++) deleteDir(dirs.get(i));
    }

    /** Run ids start with the start time in ms, so name order is time order. */
    private static List<File> sortedDirs(Context ctx) {
        File[] ds = root(ctx).listFiles(File::isDirectory);
        List<File> dirs = ds == null ? new ArrayList<File>() : new ArrayList<>(Arrays.asList(ds));
        Collections.sort(dirs, new Comparator<File>() {
            @Override public int compare(File a, File b) { return idTime(b) < idTime(a) ? -1 : idTime(b) == idTime(a) ? 0 : 1; }
        });
        return dirs;
    }

    private static long idTime(File d) {
        String n = d.getName();
        int dash = n.indexOf('-');
        try {
            return Long.parseLong(dash > 0 ? n.substring(0, dash) : n);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }
}
