package com.artemis.ondevice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Stacked bar per step: read screen (bottom), model, action (top); height = step duration. */
public class StepChart extends View {

    private final Paint obs = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint llm = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint act = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private List<RunRecord.Step> steps = new ArrayList<>();

    public StepChart(Context c, AttributeSet a) {
        super(c, a);
        obs.setColor(c.getColor(R.color.bar_obs));
        llm.setColor(c.getColor(R.color.bar_llm));
        act.setColor(c.getColor(R.color.bar_act));
        grid.setColor(c.getColor(R.color.outline));
        grid.setStrokeWidth(dp(1));
        label.setColor(c.getColor(R.color.text_secondary));
        label.setTextSize(dp(10));
    }

    void setSteps(List<RunRecord.Step> s) {
        steps = new ArrayList<>(s);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float top = dp(14), bottom = getHeight() - dp(16), left = 0, right = getWidth();
        long max = 1;
        for (RunRecord.Step s : steps) max = Math.max(max, s.totalMs());
        c.drawLine(left, bottom, right, bottom, grid);
        c.drawLine(left, top, right, top, grid);
        c.drawText(Format.duration(max), left, top - dp(4), label);
        if (steps.isEmpty()) return;

        float slot = (right - left) / steps.size();
        float bar = Math.min(dp(28), slot * 0.7f);
        float scale = (bottom - top) / max;
        float radius = Math.min(dp(4), bar / 3);
        int labelEvery = Math.max(1, (int) Math.ceil(steps.size() / 12f));
        for (int i = 0; i < steps.size(); i++) {
            RunRecord.Step s = steps.get(i);
            float cx = left + slot * i + slot / 2, x0 = cx - bar / 2, x1 = cx + bar / 2;
            float y = bottom;
            y = segment(c, x0, x1, y, s.obsMs * scale, obs, 0);
            y = segment(c, x0, x1, y, s.llmMs * scale, llm, 0);
            segment(c, x0, x1, y, s.actMs * scale, act, radius);
            if (i % labelEvery == 0) {
                String n = String.valueOf(s.index);
                c.drawText(n, cx - label.measureText(n) / 2, getHeight() - dp(2), label);
            }
        }
    }

    private float segment(Canvas c, float x0, float x1, float y, float h, Paint p, float radius) {
        if (h <= 0) return y;
        r.set(x0, y - h, x1, y);
        if (radius > 0) c.drawRoundRect(r, radius, radius, p);
        else c.drawRect(r, p);
        return y - h;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
