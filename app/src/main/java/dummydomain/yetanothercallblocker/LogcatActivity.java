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

    private static final Logger LOG = LoggerFactory.getLogger(LogcatActivity.class);

    /** How much of it is read. Enough to cover a build; not enough to run out of memory. */
    private static final int LINES = 3000;

    public static Intent getIntent(Context context) {
        return new Intent(context, LogcatActivity.class);
    }

    private TextView logTextView;
    private ScrollView scrollView;
    private EditText filterEditText;

    /** What was read, unfiltered, so that typing in the filter doesn't read it again. */
    private List<String> lines = new ArrayList<>();

    private AsyncTask<Void, Void, List<String>> loadTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logcat);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        logTextView = findViewById(R.id.log);
        scrollView = findViewById(R.id.scroll);
        filterEditText = findViewById(R.id.filter);

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
        return true;
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
