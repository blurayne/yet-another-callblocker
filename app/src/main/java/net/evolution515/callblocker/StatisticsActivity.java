package net.evolution515.callblocker;

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

import net.evolution515.callblocker.data.GeoLookup;
import net.evolution515.callblocker.data.OriginStats;
import net.evolution515.callblocker.data.SiaNumberCategoryUtils;
import net.evolution515.callblocker.data.YacbHolder;
import net.evolution515.callblocker.data.numbers.NumberFlags;
import net.evolution515.callblocker.data.numbers.NumbersStats;

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

    /** For the lists by origin: which rating, which country, which place, and what to call it. */
    private static final String PARAM_RATING = "rating";
    private static final String PARAM_REGION = "region";
    private static final String PARAM_PLACE = "place";
    private static final String PARAM_TITLE = "title";

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
        SOURCES(R.string.stats_sources),

        // by where the numbers are from: country, then the numbers - or country, place, numbers
        SPAM_COUNTRIES(R.string.stats_spam_countries),
        SPAM_COUNTRIES_PLACES(R.string.stats_spam_countries_places),
        GOOD_COUNTRIES(R.string.stats_good_countries),
        GOOD_COUNTRIES_PLACES(R.string.stats_good_countries_places),

        // the steps down, which are not in the menu: they are opened from a row
        PLACES(R.string.stats_places),
        ORIGIN_NUMBERS(R.string.stats_top_negative);

        final int titleResId;

        Kind(int titleResId) {
            this.titleResId = titleResId;
        }

        boolean isTop() {
            return this == TOP_NEGATIVE || this == TOP_POSITIVE || this == ORIGIN_NUMBERS;
        }

        boolean isCountries() {
            return this == SPAM_COUNTRIES || this == SPAM_COUNTRIES_PLACES
                    || this == GOOD_COUNTRIES || this == GOOD_COUNTRIES_PLACES;
        }

        boolean isStep() {
            return this == PLACES || this == ORIGIN_NUMBERS;
        }

        /** Which rating a country list is about; the steps down are told. */
        int rating() {
            return this == TOP_POSITIVE || this == GOOD_COUNTRIES || this == GOOD_COUNTRIES_PLACES
                    ? NumberFlags.RATING_POSITIVE : NumberFlags.RATING_NEGATIVE;
        }
    }

    /** One line on the screen: what it is, what it says, and where it leads. */
    private static class Row {

        final String title;
        final String subtitle;
        final String value;
        final Kind opens;
        final String number;

        /** Where the row leads when it isn't a plain kind or a number: a step down. */
        Intent intent;

        /** A heading between groups of rows, which leads nowhere. */
        boolean header;

        static Row header(String title) {
            Row row = new Row(title, null, null, null, null);
            row.header = true;
            return row;
        }

        Row(String title, String subtitle, String value, Kind opens, String number) {
            this.title = title;
            this.subtitle = subtitle;
            this.value = value;
            this.opens = opens;
            this.number = number;
        }

        Row leadingTo(Intent intent) {
            this.intent = intent;
            return this;
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

    /** For the steps down: what they are about. */
    private int rating = NumberFlags.RATING_NEGATIVE;
    private String region;
    private int placeId = -1;

    /** Whether the companies ran out and the strongest numbers stand in for them. */
    private boolean fellBack;

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

        Intent intent = getIntent();
        rating = intent.getIntExtra(PARAM_RATING, kind.rating());
        region = intent.getStringExtra(PARAM_REGION);
        placeId = intent.getIntExtra(PARAM_PLACE, -1);

        // a step down is called what it is about, and says which way the ratings go
        if (kind.isStep()) {
            String title = intent.getStringExtra(PARAM_TITLE);
            if (!TextUtils.isEmpty(title)) setTitle(title);

            if (getSupportActionBar() != null) {
                getSupportActionBar().setSubtitle(rating == NumberFlags.RATING_POSITIVE
                        ? R.string.stats_top_positive : R.string.stats_top_negative);
            }
        }

        hintView = findViewById(R.id.hint);

        // the hint that blames the filter leads to it
        hintView.setOnClickListener(v -> {
            if (isFiltering()) startActivity(SettingsActivity.getIntent(this,
                    SettingsActivity.SCREEN_DB_FILTERING));
        });

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
                fellBack = false;
                return compute(stats, namedOnly);
            }

            @Override
            protected void onPostExecute(List<Row> result) {
                if (isCancelled()) return;

                String hint = fellBack ? getString(R.string.stats_no_companies)
                        : result.size() <= 1 ? fewHint(namedOnly) : null;

                if (hint == null) {
                    hint = result.isEmpty() ? getString(R.string.stats_nothing) : hint(namedOnly);
                }

                show(result, hint);
            }
        };

        task.execute();
    }

    /** Whether the database is filtered, which is the usual reason for a short list. */
    private static boolean isFiltering() {
        Settings settings = App.getSettings();

        return settings != null && settings.isDbFilteringEnabled()
                && !TextUtils.isEmpty(settings.getDbFilteringPattern());
    }

    /**
     * Why a list may hold nothing or one thing, when there is a likely reason.
     *
     * <p>The filter is the usual one: it leaves the numbers of a few countries, and with the
     * others their company names and categories. For the top lists the toggle is the other.
     */
    private String fewHint(boolean namedOnly) {
        if (kind != Kind.CATEGORIES && !kind.isTop() && !kind.isCountries()
                && kind != Kind.PLACES) {
            return null;
        }

        StringBuilder hint = new StringBuilder();

        if (isFiltering()) {
            hint.append(getString(R.string.stats_few_filtered,
                    App.getSettings().getDbFilteringPattern()));
        }

        if (kind.isTop() && namedOnly) {
            if (hint.length() > 0) hint.append("\n\n");
            hint.append(getString(R.string.stats_few_named_only));
        }

        return hint.length() > 0 ? hint.toString() : null;
    }

    /** What the list says above itself, when it has something to say. */
    private String hint(boolean namedOnly) {
        if (kind.isCountries() || kind == Kind.PLACES) return getString(R.string.stats_origin_hint);

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

    /**
     * The page as a person looks for things on it: what is calling that shouldn't, what is
     * calling that is fine, and then the database itself. Each rating in the same order - the
     * strongest numbers, then by country, then by country and place - and the entries that
     * lead somewhere say where.
     */
    private List<Row> menuRows() {
        List<Row> list = new ArrayList<>();

        list.add(Row.header(getString(R.string.stats_section_spam)));
        list.add(menuRow(Kind.TOP_NEGATIVE, R.string.stats_top_negative,
                R.string.stats_top_negative_sub));
        list.add(menuRow(Kind.SPAM_COUNTRIES, R.string.stats_by_country,
                R.string.stats_by_country_sub));
        list.add(menuRow(Kind.SPAM_COUNTRIES_PLACES, R.string.stats_by_country_place,
                R.string.stats_by_country_place_sub));

        list.add(Row.header(getString(R.string.stats_section_good)));
        list.add(menuRow(Kind.TOP_POSITIVE, R.string.stats_top_positive,
                R.string.stats_top_positive_sub));
        list.add(menuRow(Kind.GOOD_COUNTRIES, R.string.stats_by_country,
                R.string.stats_by_country_sub));
        list.add(menuRow(Kind.GOOD_COUNTRIES_PLACES, R.string.stats_by_country_place,
                R.string.stats_by_country_place_sub));

        list.add(Row.header(getString(R.string.stats_section_database)));
        list.add(menuRow(Kind.OVERVIEW, R.string.stats_overview, 0));
        list.add(menuRow(Kind.CATEGORIES, R.string.stats_categories, 0));
        list.add(menuRow(Kind.SOURCES, R.string.stats_sources, 0));

        return list;
    }

    private Row menuRow(Kind target, int title, int subtitle) {
        return new Row(getString(title), subtitle != 0 ? getString(subtitle) : null, null,
                target, null);
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
                list.add(Row.text(getString(R.string.stats_filtered), overview.filtered
                        && isFiltering()
                        ? getString(R.string.stats_filtered_pattern,
                                App.getSettings().getDbFilteringPattern())
                        : getString(overview.filtered ? R.string.stats_yes : R.string.stats_no)));

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
                int position = 0;

                for (NumbersStats.Entry entry : stats.top(rating, namedOnly, TOP_LIMIT)) {
                    list.add(entryRow(++position, entry.number, entry.name, entry.category,
                            entry.score));
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

            case SPAM_COUNTRIES:
            case SPAM_COUNTRIES_PLACES:
            case GOOD_COUNTRIES:
            case GOOD_COUNTRIES_PLACES: {
                OriginStats origins = origins();
                if (origins == null) break;

                boolean withPlaces = kind == Kind.SPAM_COUNTRIES_PLACES
                        || kind == Kind.GOOD_COUNTRIES_PLACES;

                for (OriginStats.Count count : origins.countries(rating)) {
                    boolean known = !TextUtils.isEmpty(count.region);
                    String name = known ? count.name : getString(R.string.stats_unknown_country);

                    Row row = Row.figure(name, count.count);

                    // an unknown country has no places and no range of numbers to read
                    if (known) {
                        row.leadingTo(withPlaces
                                ? stepIntent(Kind.PLACES, count.region, -1, name)
                                : stepIntent(Kind.ORIGIN_NUMBERS, count.region, -1, name));
                    }

                    list.add(row);
                }
                break;
            }

            case PLACES: {
                OriginStats origins = origins();
                if (origins == null || region == null) break;

                String country = OriginStats.countryName(region);

                for (OriginStats.Count count : origins.places(region, rating)) {
                    boolean placeKnown = count.placeId != OriginStats.NO_PLACE
                            && !TextUtils.isEmpty(count.name);
                    String name = placeKnown ? count.name : getString(R.string.stats_no_place);

                    list.add(Row.figure(name, count.count).leadingTo(stepIntent(
                            Kind.ORIGIN_NUMBERS, region, count.placeId,
                            placeKnown ? name + ", " + country : country)));
                }
                break;
            }

            case ORIGIN_NUMBERS: {
                OriginStats origins = origins();
                if (origins == null || region == null) break;

                boolean[] fell = {false};
                int position = 0;

                for (OriginStats.Entry entry : origins.numbers(region, placeId, rating,
                        namedOnly, TOP_LIMIT, fell)) {
                    list.add(entryRow(++position, entry.number, entry.name, entry.category,
                            entry.score));
                }

                fellBack = fell[0];
                break;
            }

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

    /**
     * A number of a top list: the company when one is known, with the number under it, or
     * the number itself; the category; and how strongly it is rated, as a plain count.
     */
    private Row entryRow(int position, long numberValue, String name, int categoryId, int score) {
        String number = "+" + numberValue;
        String category = SiaNumberCategoryUtils.getName(this, categoryId);

        // the score is negative ratings over positive ones
        int strength = rating == NumberFlags.RATING_NEGATIVE ? score : -score;

        String subtitle = !TextUtils.isEmpty(name) ? number : null;
        if (!TextUtils.isEmpty(category)) {
            subtitle = subtitle != null ? subtitle + " \u00b7 " + category : category;
        }

        return new Row(position + ". " + (!TextUtils.isEmpty(name) ? name : number), subtitle,
                NumberFormat.getInstance().format(Math.max(1, strength)), null, number);
    }

    private Intent stepIntent(Kind step, String stepRegion, int stepPlace, String title) {
        return getIntent(this, step)
                .putExtra(PARAM_RATING, rating)
                .putExtra(PARAM_REGION, stepRegion)
                .putExtra(PARAM_PLACE, stepPlace)
                .putExtra(PARAM_TITLE, title);
    }

    /** The lists by origin, or null when the place data can't be read. */
    private OriginStats origins() {
        GeoLookup geo = YacbHolder.getGeoLookup();
        return geo != null ? new OriginStats(this, geo) : null;
    }

    private void onRowClicked(Row row) {
        if (row.intent != null) {
            startActivity(row.intent);
        } else if (row.opens != null) {
            startActivity(getIntent(this, row.opens));
        } else if (row.number != null) {
            startActivity(InfoDialogActivity.getIntent(this, row.number));
        }
    }

    private class Adapter extends RecyclerView.Adapter<ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(
                    viewType == 1 ? R.layout.statistics_header : R.layout.statistics_item,
                    parent, false));
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).header ? 1 : 0;
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
                if (position != RecyclerView.NO_POSITION && !rows.get(position).header) {
                    onRowClicked(rows.get(position));
                }
            });
        }

        void bind(Row row) {
            title.setText(row.title);

            // a heading is its title and nothing else
            if (subtitle != null) {
                subtitle.setText(row.subtitle);
                subtitle.setVisibility(TextUtils.isEmpty(row.subtitle) ? View.GONE : View.VISIBLE);
            }

            boolean leads = row.opens != null || row.number != null || row.intent != null;

            if (value != null) {
                // a row that leads further and has no figure of its own says so with a chevron
                String shown = !TextUtils.isEmpty(row.value) ? row.value
                        : leads && !row.header ? "\u203a" : null;

                value.setText(shown);
                value.setVisibility(TextUtils.isEmpty(shown) ? View.GONE : View.VISIBLE);
            }

            itemView.setClickable(leads);
            itemView.setEnabled(leads || !row.header);
        }

    }

}
