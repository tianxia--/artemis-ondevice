package com.artemis.ondevice;

import android.content.Context;
import android.text.format.DateUtils;

import java.util.Locale;

/** Display strings shared by the screens, the bubble and notifications. */
final class Format {

    private Format() {}

    static String duration(long ms) {
        if (ms < 60_000) return String.format(Locale.US, "%.1fs", ms / 1000f);
        long s = ms / 1000;
        return String.format(Locale.US, "%dm %02ds", s / 60, s % 60);
    }

    static String seconds(long ms) {
        return String.format(Locale.US, "%.2fs", ms / 1000f);
    }

    static String tokens(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1000) return String.format(Locale.US, "%.1fk", n / 1000f);
        return String.valueOf(n);
    }

    static String when(Context c, long at) {
        int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_NUMERIC_DATE;
        if (!DateUtils.isToday(at)) flags |= DateUtils.FORMAT_SHOW_DATE;
        return DateUtils.formatDateTime(c, at, flags);
    }

    static String status(Context c, String status) {
        switch (status) {
            case RunRecord.RUNNING: return c.getString(R.string.status_running);
            case RunRecord.COMPLETED: return c.getString(R.string.status_completed);
            case RunRecord.FAILED: return c.getString(R.string.status_failed);
            case RunRecord.STOPPED: return c.getString(R.string.status_stopped);
            default: return c.getString(R.string.status_error);
        }
    }

    static int statusColor(Context c, String status) {
        switch (status) {
            case RunRecord.RUNNING: return c.getColor(R.color.brand);
            case RunRecord.COMPLETED: return c.getColor(R.color.success);
            case RunRecord.STOPPED: return c.getColor(R.color.neutral);
            default: return c.getColor(R.color.danger);
        }
    }

    static String phase(Context c, RunState.Phase p) {
        switch (p) {
            case OBSERVING: return c.getString(R.string.phase_observing);
            case THINKING: return c.getString(R.string.phase_thinking);
            case ACTING: return c.getString(R.string.phase_acting);
            case WAITING_USER: return c.getString(R.string.phase_waiting_user);
            default: return c.getString(R.string.phase_idle);
        }
    }

    /** Colors a round status badge (bubble_core background) and sets its icon. */
    static void badge(android.view.View circle, android.widget.ImageView icon, String status) {
        android.content.Context c = circle.getContext();
        ((android.graphics.drawable.GradientDrawable) circle.getBackground().mutate()).setColor(statusColor(c, status));
        icon.setImageResource(RunRecord.COMPLETED.equals(status) ? R.drawable.ic_check
                : RunRecord.STOPPED.equals(status) ? R.drawable.ic_stop
                : RunRecord.RUNNING.equals(status) ? R.drawable.ic_spark : R.drawable.ic_error);
    }
}
