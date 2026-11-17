package com.artemis.ondevice;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs the logs for troubleshooting into one zip and shares it: the daily log files, the app and
 * device, the settings (no keys or addresses), the model services (names and counts only) and a
 * summary of recent tasks (status, steps, timings, tokens; no task text, answers or screenshots).
 */
final class LogExport {

    private static final int RECENT_RUNS = 50;

    private LogExport() {}

    static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Builds the zip (call off the main thread). */
    static File build(Context ctx) throws IOException {
        AppLog.i(AppLog.APP, "log export requested");
        AppLog.flush();
        File dir = new File(ctx.getCacheDir(), "exports");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File zip = new File(dir, "artemis-logs-" + stamp + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            text(z, "info.txt", info(ctx));
            text(z, "runs.txt", runs(ctx));
            File[] logs = new File(ctx.getFilesDir(), AppLog.DIR).listFiles((d, n) -> n.endsWith(".log"));
            if (logs != null) {
                Arrays.sort(logs);
                for (File f : logs) file(z, "logs/" + f.getName(), f);
            }
        }
        return zip;
    }

    /** Opens the share sheet for the zip. */
    static void share(Context ctx, File zip) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", zip);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, zip.getName())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(zip.getName(), uri));
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.export_logs)));
    }

    private static String info(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Artemis On-Device ").append(versionName(ctx)).append('\n')
                .append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                .append("Exported: ").append(new Date()).append("\n\n");
        sb.append("# Settings\n");
        SharedPreferences prefs = ctx.getSharedPreferences("artemis_settings", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : new TreeMap<>(prefs.getAll()).entrySet()) {
            sb.append(e.getKey()).append(" = ").append(AppLog.redact(String.valueOf(e.getValue()))).append('\n');
        }
        sb.append("voiceprint = ").append(Voiceprint.enrolled(ctx) ? "recorded" : "none").append('\n');
        sb.append("accessibility = ").append(AgentAccessibilityService.get() != null ? "on" : "off").append("\n\n");
        sb.append("# Model services (no addresses or keys)\n");
        try {
            ProviderStore.Active active = ProviderStore.getActive(ctx), voice = ProviderStore.getVoice(ctx);
            for (ProviderStore.Provider p : ProviderStore.list(ctx)) {
                sb.append(p.id).append(": ").append(p.api)
                        .append(", task models ").append(p.models(false).size())
                        .append(", voice models ").append(p.models(true).size())
                        .append(", realtime url ").append(p.realtimeUrl.isEmpty() ? "no" : "yes")
                        .append(", key ").append(ProviderStore.hasApiKey(ctx, p.id) ? "stored" : "missing").append('\n');
            }
            sb.append("task model = ").append(active == null ? "none" : active.provider + " / " + active.model).append('\n');
            sb.append("voice call model = ").append(voice == null ? "offline" : voice.provider + " / " + voice.model).append('\n');
        } catch (Exception e) {
            sb.append("unreadable: ").append(e.getClass().getSimpleName()).append('\n');
        }
        sb.append("\n# Usage today\n").append(UsageStore.todayTokens(ctx)).append(" tokens\n");
        return sb.toString();
    }

    private static String runs(Context ctx) {
        StringBuilder sb = new StringBuilder("# Recent tasks (newest first; task text and answers left out)\n"
                + "id | status | steps | seconds | tokens in/out | model | skill\n");
        List<RunRecord> all = RunStore.list(ctx);
        for (int i = 0; i < Math.min(RECENT_RUNS, all.size()); i++) {
            RunRecord r = all.get(i);
            sb.append(r.id).append(" | ").append(r.status).append(" | ").append(r.steps.size())
                    .append(" | ").append(r.durationMs() / 1000).append(" | ").append(r.promptTokens()).append('/')
                    .append(r.completionTokens()).append(" | ").append(r.model)
                    .append(" | ").append(r.skillId.isEmpty() ? "-" : "replayed").append('\n');
        }
        return sb.toString();
    }

    private static void text(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static void file(ZipOutputStream z, String name, File f) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) z.write(buf, 0, n);
        }
        z.closeEntry();
    }
}
