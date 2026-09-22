package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.data.SiaNumberCategoryUtils;
import dummydomain.yetanothercallblocker.data.numbers.NumberFlags;
import dummydomain.yetanothercallblocker.data.numbers.NumbersStats;

/**
 * Figures about the database, and the numbers that stand out in it.
 *
 * <p>One screen in two shapes: opened from the main menu it is a list of the things it can
 * show, and each of those opens it again on that thing. The top lists can be narrowed to
 * the numbers some source has a business name for, which is where a negative rating says
 * the most - a company that is reported by hundreds of people is a company, not a number.
 */
public class StatisticsActivity extends AppCompatActivity {

    private static final String PARAM_KIND = "kind";

    private static final String PREFS = "statistics";
    private static final String PREF_NAMED_ONLY = "namedOnly";

    /** How many numbers a top list shows. */
    private static final int TOP_LIMIT = 100;

    private enum Kind {
        MENU(R.string.open_statistics_activity),
        OVERVIEW(R.string.stats_overview),
        TOP_NEGATIVE(R.string.stats_top_negative),
        TOP_POSITIVE(R.string.stats_top_positive),
        CATEGORIES(R.string.stats_categories),
        SOURCES(R.string.stats_sources);

        final int titleResId;

        Kind(int titleResId) {
            this.titleResId = titleResId;
        }

        boolean isTop() {
            return this == TOP_NEGATIVE || this == TOP_POSITIVE;
        }
    }

    /** One line on the screen: what it is, what it says, and where it leads. */
    private static class Row {

        final String title;
        final String subtitle;
        final String value;
        final Kind opens;
        final String number;

        Row(String title, String subtitle, String value, Kind opens, String number) {
            this.title = title;
            this.subtitle = subtitle;
            this.value = value;
            this.opens = opens;
            this.number = number;
        }

        static Row menu(Context context, Kind kind) {
            return new Row(context.getString(kind.titleResId), null, null, kind, null);
        }

        static Row figure(String title, long value) {
            return new Row(title, null, NumberFormat.getInstance().format(value), null, null);
        }

        static Row text(String title, String value) {
            return new Row(title, null, value, null, null);
        }

    }

    public static Intent getIntent(Context context) {
        return new Intent(context, StatisticsActivity.class);
    }

    private static Intent getIntent(Context context, Kind kind) {
        return getIntent(context).putExtra(PARAM_KIND, kind.name());
    }

    private Kind kind = Kind.MENU;

    private final List<Row> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    private TextView hintView;

    private AsyncTask<Void, Void, List<Row>> task;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_statistics);

        String name = getIntent().getStringExtra(PARAM_KIND);
        if (name != null) {
            try {
                kind = Kind.valueOf(name);
            } catch (IllegalArgumentException e) {
                kind = Kind.MENU;
            }
        }

        setTitle(kind.titleResId);

        hintView = findViewById(R.id.hint);

        RecyclerView list = findViewById(R.id.list);
        list.setAdapter(adapter);
    }

    @Override
    protected void onStart() {
        super.onStart();

        load();
    }

    @Override
    protected void onStop() {
        if (task != null) task.cancel(true);
        task = null;

        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!kind.isTop()) return false;

        getMenuInflater().inflate(R.menu.activity_statistics, menu);

        menu.findItem(R.id.menu_named_only).setChecked(isNamedOnly());

        return true;
    }

    /** The toggle between every number and only the ones a company is known for. */
    public void onNamedOnlyClicked(MenuItem item) {
        boolean namedOnly = !item.isChecked();
        item.setChecked(namedOnly);

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_NAMED_ONLY, namedOnly).apply();

        load();
    }

    private boolean isNamedOnly() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_NAMED_ONLY, true);
    }

    @SuppressLint("StaticFieldLeak") // cancelled in onStop
    private void load() {
        if (task != null) task.cancel(true);

        if (kind == Kind.MENU) {
            show(menuRows(), null);
            return;
        }

        NumbersStats stats = new NumbersStats(this);

        if (!stats.isAvailable()) {
            show(new ArrayList<>(), getString(R.string.stats_empty));
            return;
        }

        hintView.setText(R.string.stats_loading);
        hintView.setVisibility(View.VISIBLE);

        boolean namedOnly = isNamedOnly();

        task = new AsyncTask<Void, Void, List<Row>>() {
            @Override
            protected List<Row> doInBackground(Void... voids) {
                return compute(stats, namedOnly);
            }

            @Override
            protected void onPostExecute(List<Row> result) {
                if (isCancelled()) return;

                show(result, result.isEmpty() ? getString(R.string.stats_nothing) : hint(namedOnly));
            }
        };

        task.execute();
    }

    /** What the list says above itself, when it has something to say. */
    private String hint(boolean namedOnly) {
        if (!kind.isTop()) return null;

        return getString(namedOnly ? R.string.stats_top_hint_named : R.string.stats_top_hint_all,
                TOP_LIMIT);
    }

    private void show(List<Row> newRows, String hint) {
        rows.clear();
        rows.addAll(newRows);
        adapter.notifyDataSetChanged();

        hintView.setText(hint);
        hintView.setVisibility(TextUtils.isEmpty(hint) ? View.GONE : View.VISIBLE);
    }

    private List<Row> menuRows() {
        List<Row> list = new ArrayList<>();

        for (Kind each : Kind.values()) {
            if (each != Kind.MENU) list.add(Row.menu(this, each));
        }

        return list;
    }

    /** The rows for the kind this screen is on; on a background thread. */
    private List<Row> compute(NumbersStats stats, boolean namedOnly) {
        List<Row> list = new ArrayList<>();

        switch (kind) {
            case OVERVIEW: {
                NumbersStats.Overview overview = stats.overview();
                if (overview == null) break;

                list.add(Row.figure(getString(R.string.stats_numbers), overview.numbers));
                list.add(Row.figure(getString(R.string.stats_negative), overview.negative));
                list.add(Row.figure(getString(R.string.stats_positive), overview.positive));
                list.add(Row.figure(getString(R.string.stats_neutral), overview.neutral));
                list.add(Row.figure(getString(R.string.stats_names), overview.names));
                list.add(Row.figure(getString(R.string.stats_named_negative),
                        overview.namedNegative));
                list.add(Row.figure(getString(R.string.stats_deleted), overview.deleted));
                list.add(Row.text(getString(R.string.stats_size),
                        Formatter.formatShortFileSize(this, overview.size)));
                list.add(Row.text(getString(R.string.stats_filtered), getString(overview.filtered
                        ? R.string.stats_yes : R.string.stats_no)));

                if (overview.compiledTime > 0) {
                    list.add(Row.text(getString(R.string.stats_built),
                            DateUtils.getRelativeTimeSpanString(overview.compiledTime,
                                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                                    .toString()));
                }
                break;
            }

            case TOP_NEGATIVE:
            case TOP_POSITIVE: {
                int rating = kind == Kind.TOP_NEGATIVE
                        ? NumberFlags.RATING_NEGATIVE : NumberFlags.RATING_POSITIVE;

                int position = 0;

                for (NumbersStats.Entry entry : stats.top(rating, namedOnly, TOP_LIMIT)) {
                    String number = "+" + entry.number;
                    String category = SiaNumberCategoryUtils.getName(this, entry.category);

                    // the score is negative ratings over positive ones; said as a plain count
                    int score = kind == Kind.TOP_NEGATIVE ? entry.score : -entry.score;

                    String subtitle = !TextUtils.isEmpty(entry.name) ? number : null;
                    if (!TextUtils.isEmpty(category)) {
                        subtitle = subtitle != null ? subtitle + " · " + category : category;
                    }

                    list.add(new Row((++position) + ". "
                            + (!TextUtils.isEmpty(entry.name) ? entry.name : number),
                            subtitle, NumberFormat.getInstance().format(Math.max(1, score)),
                            null, number));
                }
                break;
            }

            case CATEGORIES:
                for (NumbersStats.Count count : stats.categories()) {
                    int id = Integer.parseInt(count.name);

                    String name = SiaNumberCategoryUtils.getName(this, id);
                    if (TextUtils.isEmpty(name)) name = getString(R.string.stats_no_category);

                    list.add(Row.figure(name, count.count));
                }
                break;

            case SOURCES:
                for (NumbersStats.Count count : stats.sources()) {
                    list.add(Row.figure(!TextUtils.isEmpty(count.name)
                            ? count.name : getString(R.string.stats_unnamed_source), count.count));
                }
                break;

            default:
                break;
        }

        return list;
    }

    private void onRowClicked(Row row) {
        if (row.opens != null) {
            startActivity(getIntent(this, row.opens));
        } else if (row.number != null) {
            startActivity(InfoDialogActivity.getIntent(this, row.number));
        }
    }

    private class Adapter extends RecyclerView.Adapter<ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.statistics_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(rows.get(position));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

    }

    private class ViewHolder extends RecyclerView.ViewHolder {

        final TextView title, subtitle, value;

        ViewHolder(@NonNull View itemView) {
            super(itemView);

            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            value = itemView.findViewById(R.id.value);

            itemView.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) onRowClicked(rows.get(position));
            });
        }

        void bind(Row row) {
            title.setText(row.title);

            subtitle.setText(row.subtitle);
            subtitle.setVisibility(TextUtils.isEmpty(row.subtitle) ? View.GONE : View.VISIBLE);

            value.setText(row.value);
            value.setVisibility(TextUtils.isEmpty(row.value) ? View.GONE : View.VISIBLE);

            itemView.setClickable(row.opens != null || row.number != null);
        }

    }

}
