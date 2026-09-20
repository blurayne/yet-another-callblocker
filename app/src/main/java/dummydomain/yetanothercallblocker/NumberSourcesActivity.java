package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceService;

/** The places the app gets numbers from, as a list the user can add to. */
public class NumberSourcesActivity extends AppCompatActivity {

    public static Intent getIntent(Context context) {
        return new Intent(context, NumberSourcesActivity.class);
    }

    private final SourceService sourceService = YacbHolder.getSourceService();

    private final List<NumberSource> sources = new ArrayList<>();

    private SourceAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_number_sources);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        adapter = new SourceAdapter();

        RecyclerView list = findViewById(R.id.sourcesList);
        list.setAdapter(adapter);
        list.addItemDecoration(new CustomVerticalDivider(this));
    }

    @Override
    protected void onStart() {
        super.onStart();

        reload(); // a source may have been edited on the screen this one leads to
    }

    private void reload() {
        sources.clear();
        if (sourceService != null) sources.addAll(sourceService.getSources());

        adapter.notifyDataSetChanged();
    }

    public void onAddClicked(View view) {
        startActivity(EditNumberSourceActivity.getIntent(this, null));
    }

    /** What the row says about a source: what it holds, how often, and how it last went. */
    private String getStatus(NumberSource source) {
        List<String> parts = new ArrayList<>(3);

        parts.add(getString(getTypeName(source.getType())));
        parts.add(getString(getUpdatesName(source.getUpdates())));

        if (!TextUtils.isEmpty(source.getLastResult())) {
            parts.add(source.getLastResult());
        } else if (source.getLastUpdate() > 0) {
            parts.add(getString(R.string.source_last_update, DateUtils.getRelativeTimeSpanString(
                    source.getLastUpdate(), System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS)));
        } else {
            parts.add(getString(R.string.source_never_fetched));
        }

        if (!source.isEnabled()) parts.add(getString(R.string.source_off));

        return TextUtils.join(" · ", parts);
    }

    static int getTypeName(NumberSource.Type type) {
        switch (type) {
            case PHONE_BLOCK: return R.string.source_type_phone_block;
            case CARDDAV: return R.string.source_type_carddav;
            default: return R.string.source_type_database;
        }
    }

    static int getUpdatesName(NumberSource.Updates updates) {
        switch (updates) {
            case DAILY: return R.string.source_updates_daily;
            case WEEKLY: return R.string.source_updates_weekly;
            case MONTHLY: return R.string.source_updates_monthly;
            default: return R.string.source_updates_manual;
        }
    }

    private class SourceAdapter extends RecyclerView.Adapter<SourceAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.number_source_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(sources.get(position));
        }

        @Override
        public int getItemCount() {
            return sources.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            final TextView name, url, status;

            ViewHolder(@NonNull View itemView) {
                super(itemView);

                name = itemView.findViewById(R.id.name);
                url = itemView.findViewById(R.id.url);
                status = itemView.findViewById(R.id.status);

                itemView.setOnClickListener(v -> {
                    int position = getBindingAdapterPosition();
                    if (position < 0 || position >= sources.size()) return;

                    startActivity(EditNumberSourceActivity.getIntent(
                            NumberSourcesActivity.this, sources.get(position).getId()));
                });
            }

            void bind(NumberSource source) {
                name.setText(!TextUtils.isEmpty(source.getName())
                        ? source.getName() : getString(getTypeName(source.getType())));

                url.setText(source.getUrl());
                url.setVisibility(TextUtils.isEmpty(source.getUrl()) ? View.GONE : View.VISIBLE);

                status.setText(getStatus(source));
            }
        }
    }

}
