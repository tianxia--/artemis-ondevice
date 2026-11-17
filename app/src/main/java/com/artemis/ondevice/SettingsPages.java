package com.artemis.ondevice;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Settings is a start page of groups, each opening its own page (the same activity with
 * {@link #EXTRA_PAGE}, showing one container of activity_settings.xml). Each row says what the
 * group is set to now, so most visits need not open it.
 */
final class SettingsPages {

    static final String EXTRA_PAGE = "page";

    /** Key, title, icon, container in activity_settings.xml. */
    private static final Object[][] PAGES = {
            {"model", R.string.cat_model, R.drawable.ic_spark, R.id.page_model},
            {"run", R.string.cat_run, R.drawable.ic_play, R.id.page_run},
            {"voice", R.string.cat_voice, R.drawable.ic_mic, R.id.page_voice},
            {"skills", R.string.cat_skills, R.drawable.ic_lightbulb, R.id.page_skills},
            {"mcp", R.string.cat_mcp, R.drawable.ic_link, R.id.page_mcp},
            {"general", R.string.cat_general, R.drawable.ic_settings, R.id.page_general},
    };

    private SettingsPages() {}

    /** The page container for {@code key}, 0 for the start page (no or an unknown key). */
    static int container(String key) {
        for (Object[] p : PAGES) if (p[0].equals(key)) return (int) p[3];
        return 0;
    }

    static int title(String key) {
        for (Object[] p : PAGES) if (p[0].equals(key)) return (int) p[1];
        return R.string.settings_title;
    }

    static Intent open(Context ctx, String key) {
        return new Intent(ctx, SettingsActivity.class).putExtra(EXTRA_PAGE, key);
    }

    /** Fills the start page: one row per group with its current state. */
    static void renderStart(Context ctx, LinearLayout list) {
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        for (Object[] p : PAGES) {
            String key = (String) p[0];
            View row = inflater.inflate(R.layout.item_category, list, false);
            ((ImageView) row.findViewById(R.id.category_icon)).setImageResource((int) p[2]);
            ((TextView) row.findViewById(R.id.category_title)).setText((int) p[1]);
            ((TextView) row.findViewById(R.id.category_summary)).setText(summary(ctx, key));
            row.setOnClickListener(v -> ctx.startActivity(open(ctx, key)));
            list.addView(row);
        }
    }

    private static String summary(Context ctx, String key) {
        switch (key) {
            case "model": {
                ProviderStore.Active a = ProviderStore.getActive(ctx);
                ProviderStore.Provider p = a == null ? null : ProviderStore.get(ctx, a.provider);
                return a == null || p == null ? ctx.getString(R.string.model_none)
                        : a.model + " · " + (p.name.isEmpty() ? p.id : p.name);
            }
            case "run":
                return ctx.getString(R.string.cat_run_summary, onOff(ctx, AppSettings.screenshot(ctx)),
                        onOff(ctx, AppSettings.bubble(ctx)), onOff(ctx, AppSettings.confirmSensitive(ctx)));
            case "voice": {
                ProviderStore.Active call = ProviderStore.getVoice(ctx);
                return ctx.getString(R.string.cat_voice_summary,
                        VoiceService.canRun(ctx) ? AppSettings.wakeWord(ctx) : ctx.getString(R.string.state_off),
                        call == null ? ctx.getString(R.string.voice_call_off) : call.model);
            }
            case "skills":
                return ctx.getString(R.string.cat_skills_summary, SkillStore.list(ctx).size(),
                        ExperienceStore.list(ctx).size());
            case "mcp":
                return ctx.getString(R.string.cat_mcp_summary,
                        ctx.getString(AppSettings.mcp(ctx) ? R.string.state_enabled : R.string.state_disabled));
            case "general":
                return ctx.getString(R.string.cat_general_summary, languageLabel(ctx),
                        ctx.getString(AgentAccessibilityService.get() != null ? R.string.state_enabled : R.string.state_disabled));
            default:
                return "";
        }
    }

    private static String onOff(Context ctx, boolean on) {
        return ctx.getString(on ? R.string.state_on : R.string.state_off);
    }

    private static String languageLabel(Context ctx) {
        String tags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        if (tags.startsWith("zh")) return ctx.getString(R.string.language_zh);
        if (tags.startsWith("en")) return ctx.getString(R.string.language_en);
        return ctx.getString(R.string.language_system);
    }
}
