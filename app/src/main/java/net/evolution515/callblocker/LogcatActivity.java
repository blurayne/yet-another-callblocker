package net.evolution515.callblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

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

import net.evolution515.callblocker.data.BuildLog;
import net.evolution515.callblocker.utils.DebuggingUtils;
import net.evolution515.callblocker.utils.FileUtils;

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

    /** How long the line of dots gets before it starts over. */
    private static final int MAX_DOTS = 40;

    /** What to filter it by when it opens, for "show me this source's lines". */
    private static final String PARAM_FILTER = "filter";

    private static final Logger LOG = LoggerFactory.getLogger(LogcatActivity.class);

    /** How much of it is read. Enough to cover a build; not enough to run out of memory. */
    private static final int LINES = 3000;

    /** How often it is read again while it is open. */
    private static final long RELOAD_INTERVAL_MS = 1000;

    /**
     * How far from the bottom still counts as being at it.
     *
     * <p>A line's worth, so that a screen that is a few pixels short of the end - which is
     * where one lands after scrolling with a finger - is taken to be at the end.
     */
    private static final int BOTTOM_SLACK_PX = 48;

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

    /**
     * Whether the view stays at the end as lines arrive.
     *
     * <p>On until the reader scrolls up, and on again when they scroll back down: following
     * is what someone watching a build wants, and the moment they stop to read something it
     * is the last thing they want. So it isn't a mode to remember to turn off - it is where
     * the view happens to be.
     */
    private boolean following = true;

    /** So that a refresh doesn't blank what is on screen and start again. */
    private boolean loaded;

    /**
     * Set while this scrolls the view itself.
     *
     * <p>A scroll the view does to keep up is not a reader scrolling away, and must not be
     * read as one: it goes to the end and would leave following on anyway, but the frames
     * on the way there are positions that aren't the end.
     */
    private boolean scrollingItself;

    /** What is on screen, to leave it alone when the log hasn't changed. */
    private String shown;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** How many dots the running line has right now; it grows by one a second. */
    private int dots;

    /** Reads it again, over and over, for as long as the screen is in front of someone. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            load();
            showRunning();

            handler.postDelayed(this, RELOAD_INTERVAL_MS);
        }
    };

    /**
     * A line of dots under the log while a build runs, one more every second.
     *
     * <p>The log says what the build is doing, not that it still is: a download that is
     * quiet for a minute and a build that was killed a minute ago look the same in it. The
     * dots are the difference - as long as they keep coming, something is happening.
     */
    private void showRunning() {
        TextView runningView = findViewById(R.id.running);
        if (runningView == null) return;

        if (!BuildStarter.isBuilding()) {
            if (runningView.getVisibility() != View.GONE) runningView.setVisibility(View.GONE);
            dots = 0;
            return;
        }

        dots = dots % MAX_DOTS + 1;

        StringBuilder line = new StringBuilder(dots);
        for (int i = 0; i < dots; i++) line.append('.');

        runningView.setText(line);
        runningView.setVisibility(View.VISIBLE);

        if (following) scrollToEndNow();
    }

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

        /*
         * Following is a place rather than a setting: at the end means follow, anywhere else
         * means the reader is reading and is not to be dragged away from it.
         */
        scrollView.getViewTreeObserver().addOnScrollChangedListener(
                new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        // a finger that goes anywhere but the end turns following off,
                        // and one that comes back turns it on again; the view's own
                        // scrolling says nothing about what the reader wants
                        if (!scrollingItself) following = atBottom();
                    }
                });

        /*
         * The moment the text has grown is the moment to be at its end again - after the
         * layout that made it taller and before the frame that would show the old place.
         * Doing it here rather than a post() later is the difference between a view that
         * keeps up and one that visibly jumps every second.
         */
        logTextView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (following && bottom - top != oldBottom - oldTop) scrollToEndNow();
                });

        filterEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                shown = null; // the filter changed, so what is on screen has to change with it

                showLines();
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

        /*
         * A build writes into it while this is open, so it is read again and again for as
         * long as someone is looking at it - which is the whole point of having it on screen
         * while a build runs. The app's own log is not followed: reading that one means
         * starting a process, and doing that every second to catch nothing is not worth it.
         */
        if (buildLog) handler.postDelayed(tick, RELOAD_INTERVAL_MS);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);

        super.onStop();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public void onRefreshClicked(MenuItem item) {
        following = true; // asking for it again is asking for the end of it

        scrollToEndNow();
        load();
    }

    /** Whether the view is at the end of the log, give or take a line. */
    private boolean atBottom() {
        View content = scrollView.getChildCount() != 0 ? scrollView.getChildAt(0) : null;
        if (content == null) return true;

        int left = content.getBottom() - scrollView.getHeight() - scrollView.getScrollY();

        return left <= BOTTOM_SLACK_PX;
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
        handler.removeCallbacks(tick);

        if (loadTask != null) {
            loadTask.cancel(true);
            loadTask = null;
        }

        super.onDestroy();
    }

    @SuppressLint("StaticFieldLeak") // it reads a few thousand lines and is cancelled below
    private void load() {
        // only the first time: a refresh that blanks the screen first is a flicker every second
        if (!loaded) logTextView.setText(R.string.logcat_loading);

        AsyncTask<Void, Void, List<String>> task = loadTask
                = new AsyncTask<Void, Void, List<String>>() {
            @Override
            protected List<String> doInBackground(Void... voids) {
                return read();
            }

            @Override
            protected void onPostExecute(List<String> read) {
                lines = read;
                loaded = true;

                showLines();
            }
        };

        task.execute();
    }

    /** What the filter lets through, or everything when there is none. */
    private void showLines() {
        String filter = filterEditText.getText().toString().trim().toLowerCase(Locale.ROOT);

        StringBuilder builder = new StringBuilder();

        for (String line : lines) {
            if (!filter.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(filter)) continue;

            if (builder.length() != 0) builder.append('\n');

            builder.append(line);
        }

        String text = builder.length() != 0
                ? builder.toString() : getString(R.string.logcat_empty);

        /*
         * A log that hasn't changed is left exactly as it is. Setting the same text again
         * would be a second of work every second, and would take the reader's place in it
         * away from them for no reason at all.
         */
        if (text.equals(shown)) return;

        /*
         * A log grows at the end, so what is new is what comes after what is already on
         * screen - and only that is put there. Replacing the whole text every second lays
         * the whole thing out again and lands the reader wherever that leaves them; adding
         * a line moves nothing that was already there.
         */
        if (shown != null && text.length() > shown.length() && text.startsWith(shown)) {
            logTextView.append(text.substring(shown.length()));
        } else {
            logTextView.setText(text);
        }

        shown = text;

        // the layout listener takes it from here when the text got taller; when it didn't
        // - a filter that matched nothing new - there is nothing to scroll to
    }

    /**
     * Puts the view at the end, this instant.
     *
     * <p>Not {@link ScrollView#fullScroll}: that one slides there over several frames, and
     * every frame of it is a position that isn't the end. And not a post(): that is a frame
     * later, which is one frame of the old place on screen - the jump.
     */
    private void scrollToEndNow() {
        View content = scrollView.getChildCount() != 0 ? scrollView.getChildAt(0) : null;
        if (content == null) return;

        scrollingItself = true;
        try {
            scrollView.scrollTo(0, Math.max(0, content.getBottom() - scrollView.getHeight()));
        } finally {
            scrollingItself = false;
        }
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
