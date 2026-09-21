package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dummydomain.yetanothercallblocker.data.BuildLog;
import dummydomain.yetanothercallblocker.event.DbCompileProgressEvent;
import dummydomain.yetanothercallblocker.utils.DebuggingUtils;
import dummydomain.yetanothercallblocker.utils.FileUtils;

/**
 * The app's own log, in the app.
 *
 * <p>Reading it needs no permission and no computer, because what an app is shown is its own
 * process's log and nothing else. That is also the limit of it: the lines from before a crash
 * belong to the process that crashed, and this one can't see them. For those there are the
 * crash reports next to this.
 */
public class LogcatActivity extends AppCompatActivity {

    /** Which log this screen is showing. */
    private static final String PARAM_BUILD_LOG = "buildLog";

    /** What to filter it by when it opens, for "show me this source's lines". */
    private static final String PARAM_FILTER = "filter";

    private static final Logger LOG = LoggerFactory.getLogger(LogcatActivity.class);

    /** How much of it is read. Enough to cover a build; not enough to run out of memory. */
    private static final int LINES = 3000;

    /** How often it is read again while a build is writing into it. */
    private static final long RELOAD_INTERVAL_MS = 2000;

    private long lastReloaded;

    public static Intent getIntent(Context context) {
        return new Intent(context, LogcatActivity.class);
    }

    /**
     * The build log rather than the app's log.
     *
     * @param filter what to show of it to begin with, or null for all of it
     */
    public static Intent getBuildLogIntent(Context context, String filter) {
        Intent intent = new Intent(context, LogcatActivity.class);

        intent.putExtra(PARAM_BUILD_LOG, true);
        if (filter != null) intent.putExtra(PARAM_FILTER, filter);

        return intent;
    }

    private TextView logTextView;
    private ScrollView scrollView;
    private EditText filterEditText;

    /** What was read, unfiltered, so that typing in the filter doesn't read it again. */
    private List<String> lines = new ArrayList<>();

    private AsyncTask<Void, Void, List<String>> loadTask;

    /** Whether this is the build log; the other one is what the app itself wrote. */
    private boolean buildLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logcat);

        buildLog = getIntent().getBooleanExtra(PARAM_BUILD_LOG, false);

        setTitle(buildLog ? R.string.build_log : R.string.logcat_activity);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        logTextView = findViewById(R.id.log);
        scrollView = findViewById(R.id.scroll);
        filterEditText = findViewById(R.id.filter);

        String filter = getIntent().getStringExtra(PARAM_FILTER);
        if (filter != null) filterEditText.setText(filter);

        filterEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                showLines(false);
            }
        });

        load();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_logcat, menu);

        // the app's log isn't the app's to throw away; the build log is
        menu.findItem(R.id.menu_clear).setVisible(buildLog);

        return true;
    }

    public void onClearClicked(MenuItem item) {
        new BuildLog(this).clear();

        load();
    }

    @Override
    protected void onStart() {
        super.onStart();

        // a build writes into it while this is open, so it follows along
        if (buildLog) EventUtils.register(this);
    }

    @Override
    protected void onStop() {
        if (buildLog) EventUtils.unregister(this);

        super.onStop();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onDbCompileProgress(DbCompileProgressEvent event) {
        long now = System.currentTimeMillis();

        if (now - lastReloaded < RELOAD_INTERVAL_MS) return;

        lastReloaded = now;

        load();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public void onRefreshClicked(MenuItem item) {
        load();
    }

    /** Hands the whole log over as a file, for when it has to go somewhere else. */
    public void onShareClicked(MenuItem item) {
        if (buildLog) {
            File file = BuildLog.getFile(this);

            if (file.exists()) {
                FileUtils.shareFile(this, file);
            } else {
                Toast.makeText(this, R.string.build_log_empty, Toast.LENGTH_SHORT).show();
            }

            return;
        }

        String path = null;
        try {
            path = DebuggingUtils.saveLogcatInCache(this);
            DebuggingUtils.appendDeviceInfo(path);
        } catch (IOException | InterruptedException e) {
            LOG.warn("onShareClicked()", e);
        }

        if (path != null) {
            FileUtils.shareFile(this, new File(path));
        } else {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (loadTask != null) {
            loadTask.cancel(true);
            loadTask = null;
        }

        super.onDestroy();
    }

    @SuppressLint("StaticFieldLeak") // it reads a few thousand lines and is cancelled below
    private void load() {
        logTextView.setText(R.string.logcat_loading);

        AsyncTask<Void, Void, List<String>> task = loadTask
                = new AsyncTask<Void, Void, List<String>>() {
            @Override
            protected List<String> doInBackground(Void... voids) {
                return read();
            }

            @Override
            protected void onPostExecute(List<String> read) {
                lines = read;

                showLines(true);
            }
        };

        task.execute();
    }

    /** What the filter lets through, or everything when there is none. */
    private void showLines(boolean scrollToEnd) {
        String filter = filterEditText.getText().toString().trim().toLowerCase(Locale.ROOT);

        StringBuilder builder = new StringBuilder();

        for (String line : lines) {
            if (!filter.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(filter)) continue;

            if (builder.length() != 0) builder.append('\n');

            builder.append(line);
        }

        logTextView.setText(builder.length() != 0
                ? builder.toString() : getString(R.string.logcat_empty));

        // the end of it is where the last thing that happened is
        if (scrollToEnd) scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private List<String> read() {
        if (buildLog) return readBuildLog();

        return readLogcat();
    }

    private List<String> readBuildLog() {
        String text = new BuildLog(this).read();

        List<String> read = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            read.add(getString(R.string.build_log_empty));
            return read;
        }

        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) read.add(line);
        }

        return read;
    }

    private List<String> readLogcat() {
        List<String> read = new ArrayList<>();

        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-v", "time", "-t", String.valueOf(LINES)});

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), Charset.forName("UTF-8")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    read.add(line);
                }
            }

            process.waitFor();
        } catch (Exception e) {
            LOG.warn("read()", e);

            read.add(getString(R.string.logcat_failed));
            read.add(String.valueOf(e));
        }

        return read;
    }

}
