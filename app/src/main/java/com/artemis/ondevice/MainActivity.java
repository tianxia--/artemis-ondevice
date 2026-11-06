package com.artemis.ondevice;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home: setup checklist, task composer, live run, overview metrics and recent runs. */
public class MainActivity extends AppCompatActivity implements RunState.Listener {

    static final String EXTRA_FOCUS_TASK = "focus_task";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RunAdapter adapter = new RunAdapter();
    private TextInputEditText taskInput;
    private Chip modelChip;
    private View setupCard, setupA11y, setupModel, composerCard, liveCard, emptyHistory;
    private TextView liveStatus, liveElapsed, liveTask, liveLast;
    private String lastRenderedRunEnd;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            renderLive();
            if (RunState.isRunning()) main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        });

        taskInput = findViewById(R.id.task_input);
        modelChip = findViewById(R.id.model_chip);
        setupCard = findViewById(R.id.setup_card);
        setupA11y = findViewById(R.id.setup_a11y);
        setupModel = findViewById(R.id.setup_model);
        composerCard = findViewById(R.id.composer_card);
        liveCard = findViewById(R.id.live_card);
        liveStatus = findViewById(R.id.live_status);
        liveElapsed = findViewById(R.id.live_elapsed);
        liveTask = findViewById(R.id.live_task);
        liveLast = findViewById(R.id.live_last);
        emptyHistory = findViewById(R.id.empty_history);

        modelChip.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.setup_a11y_button).setOnClickListener(
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.setup_model_button).setOnClickListener(
                v -> startActivity(new Intent(this, ProviderEditActivity.class)));
        findViewById(R.id.start_button).setOnClickListener(v -> start());
        findViewById(R.id.live_stop).setOnClickListener(v -> AgentService.stop(this));
        findViewById(R.id.live_details).setOnClickListener(v -> {
            RunRecord r = RunState.current();
            if (r != null) openRun(r.id);
        });
        findViewById(R.id.clear_history).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.confirm_clear_history)
                .setPositiveButton(R.string.action_clear, (d, w) -> io.execute(() -> {
                    RunStore.clear(this);
                    main.post(this::loadHistory);
                }))
                .setNegativeButton(R.string.cancel, null)
                .show());

        ChipGroup suggestions = findViewById(R.id.suggestions);
        for (int res : new int[]{R.string.suggest_battery, R.string.suggest_wifi, R.string.suggest_storage}) {
            Chip c = new Chip(this);
            c.setText(res);
            c.setOnClickListener(v -> {
                taskInput.setText(((Chip) v).getText());
                taskInput.setSelection(taskInput.length());
            });
            suggestions.addView(c);
        }

        RecyclerView list = findViewById(R.id.history);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        String task = getIntent().getStringExtra(AgentService.EXTRA_TASK);
        if (task != null) taskInput.setText(task);
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        handleFocus(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String task = intent.getStringExtra(AgentService.EXTRA_TASK);
        if (task != null) taskInput.setText(task);
        handleFocus(intent);
    }

    private void handleFocus(Intent intent) {
        if (!intent.getBooleanExtra(EXTRA_FOCUS_TASK, false)) return;
        taskInput.requestFocus();
        taskInput.postDelayed(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(taskInput, InputMethodManager.SHOW_IMPLICIT), 200);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderSetup();
        RunState.subscribe(this);
        loadHistory();
    }

    @Override
    protected void onPause() {
        RunState.unsubscribe(this);
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRunChanged() {
        renderLive();
        main.removeCallbacks(tick);
        if (RunState.isRunning()) main.postDelayed(tick, 1000);
        // Refresh the list once when a run ends.
        RunRecord r = RunState.current();
        if (r != null && !r.isRunning() && !r.id.equals(lastRenderedRunEnd)) {
            lastRenderedRunEnd = r.id;
            loadHistory();
        }
    }

    // ------------------------------------------------------------------ actions

    private void start() {
        String task = taskInput.getText() == null ? "" : taskInput.getText().toString().trim();
        if (task.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_task, Toast.LENGTH_SHORT).show();
            return;
        }
        if (RunState.isRunning()) {
            Toast.makeText(this, R.string.toast_running, Toast.LENGTH_SHORT).show();
            return;
        }
        if (AgentAccessibilityService.get() == null) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (ProviderStore.getActive(this) == null) {
            startActivity(new Intent(this, ProviderEditActivity.class));
            return;
        }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(taskInput.getWindowToken(), 0);
        taskInput.setText("");
        AgentService.start(this, task);
        // With the bubble on, step aside so the agent works on the phone and the bubble shows progress.
        if (AppSettings.bubble(this)) moveTaskToBack(true);
    }

    private void openRun(String id) {
        startActivity(new Intent(this, RunDetailActivity.class).putExtra(RunDetailActivity.EXTRA_RUN_ID, id));
    }

    // ------------------------------------------------------------------ rendering

    private void renderSetup() {
        boolean a11y = AgentAccessibilityService.get() != null;
        ProviderStore.Active active = ProviderStore.getActive(this);
        setupA11y.setVisibility(a11y ? View.GONE : View.VISIBLE);
        setupModel.setVisibility(active != null ? View.GONE : View.VISIBLE);
        setupCard.setVisibility(a11y && active != null ? View.GONE : View.VISIBLE);
        modelChip.setText(active == null ? getString(R.string.model_none) : active.model + " · " + active.provider);
    }

    private void renderLive() {
        RunRecord r = RunState.current();
        boolean running = r != null && r.isRunning();
        liveCard.setVisibility(running ? View.VISIBLE : View.GONE);
        composerCard.setVisibility(running ? View.GONE : View.VISIBLE);
        if (!running) return;
        liveStatus.setText(getString(R.string.status_progress, r.steps.size() + 1, Format.phase(this, RunState.phase())));
        liveElapsed.setText(Format.duration(r.durationMs()));
        liveTask.setText(r.task);
        RunRecord.Step last = r.lastStep();
        liveLast.setText(last == null ? getString(R.string.bubble_first_step)
                : last.actions.isEmpty() ? FlashLoop.clip(last.reasoning, 200) : TextUtils.join("\n", last.actions));
    }

    private void loadHistory() {
        io.execute(() -> {
            List<RunRecord> runs = RunStore.list(this);
            main.post(() -> {
                if (isDestroyed()) return;
                adapter.submit(runs);
                emptyHistory.setVisibility(runs.isEmpty() ? View.VISIBLE : View.GONE);
                findViewById(R.id.clear_history).setVisibility(runs.isEmpty() ? View.GONE : View.VISIBLE);
                renderOverview(runs);
            });
        });
    }

    private void renderOverview(List<RunRecord> runs) {
        int done = 0, ok = 0, steps = 0;
        long time = 0;
        for (RunRecord r : runs) {
            if (r.isRunning()) continue;
            done++;
            if (RunRecord.COMPLETED.equals(r.status)) ok++;
            steps += r.steps.size();
            time += r.durationMs();
        }
        metric(R.id.stat_runs, String.valueOf(done), R.string.overview_runs);
        metric(R.id.stat_success, done == 0 ? "–" : Math.round(100f * ok / done) + "%", R.string.overview_success);
        metric(R.id.stat_step, steps == 0 ? "–" : Format.duration(time / steps), R.string.overview_step);
    }

    private void metric(int id, String value, int label) {
        View v = findViewById(id);
        ((TextView) v.findViewById(R.id.metric_value)).setText(value);
        ((TextView) v.findViewById(R.id.metric_label)).setText(label);
    }

    // ------------------------------------------------------------------ list

    private final class RunAdapter extends RecyclerView.Adapter<RunAdapter.Holder> {
        private final List<RunRecord> items = new ArrayList<>();

        void submit(List<RunRecord> runs) {
            items.clear();
            items.addAll(runs);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_run, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            RunRecord r = items.get(position);
            h.task.setText(r.task);
            h.answer.setText(r.answer.isEmpty() ? Format.status(MainActivity.this, r.status) : r.answer);
            h.meta.setText(Format.when(MainActivity.this, r.startedAt) + " · " + Format.duration(r.durationMs())
                    + " · " + getString(R.string.n_steps, r.steps.size())
                    + " · " + Format.tokens(r.promptTokens() + r.completionTokens()) + " tok");
            Format.badge(h.badge, h.icon, r.status);
            h.itemView.setOnClickListener(v -> openRun(r.id));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView task, answer, meta;
            final View badge;
            final ImageView icon;

            Holder(View v) {
                super(v);
                task = v.findViewById(R.id.run_task);
                answer = v.findViewById(R.id.run_answer);
                meta = v.findViewById(R.id.run_meta);
                badge = v.findViewById(R.id.run_badge);
                icon = v.findViewById(R.id.run_icon);
            }
        }
    }
}
