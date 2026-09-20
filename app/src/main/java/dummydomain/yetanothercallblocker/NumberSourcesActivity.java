package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import dummydomain.yetanothercallblocker.data.PhoneBlockService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceService;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.event.PhoneBlockUpdateFinishedEvent;
import dummydomain.yetanothercallblocker.sia.model.database.DbManager;
import dummydomain.yetanothercallblocker.work.TaskService;

/**
 * The places the app gets numbers from, as a list the user can add to and put in order.
 *
 * <p>The order is what the database is built from: the first source that is switched on
 * brings the database itself, every source below it is a layer on top of what is already
 * there. So the list is not only a set of addresses - moving a row changes what the app
 * knows about a number when two sources disagree about it.
 */
public class NumberSourcesActivity extends AppCompatActivity {

    public static Intent getIntent(Context context) {
        return new Intent(context, NumberSourcesActivity.class);
    }

    private final SourceService sourceService = YacbHolder.getSourceService();

    private final List<NumberSource> sources = new ArrayList<>();

    private SourceAdapter adapter;
    private ItemTouchHelper touchHelper;

    /** Set while a row is being dragged, so the new order is written down once, at the end. */
    private boolean reordered;

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

        touchHelper = new ItemTouchHelper(new ReorderCallback());
        touchHelper.attachToRecyclerView(list);
    }

    @Override
    protected void onStart() {
        super.onStart();

        EventUtils.register(this);

        reload(); // a source may have been edited on the screen this one leads to
    }

    @Override
    protected void onStop() {
        EventUtils.unregister(this);

        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_number_sources, menu);
        return true;
    }

    /** Builds the database from the sources again, in the order the list has them. */
    public void onCompileClicked(MenuItem item) {
        Toast.makeText(this, R.string.sources_compiling, Toast.LENGTH_SHORT).show();

        TaskService.start(this, TaskService.TASK_DOWNLOAD_MAIN_DB);
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloadFinished(MainDbDownloadFinishedEvent event) {
        reload(); // every source that was asked wrote down how it went

        if (event.noSources) {
            Toast.makeText(this, R.string.sources_none_enabled, Toast.LENGTH_LONG).show();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onPhoneBlockUpdateFinished(PhoneBlockUpdateFinishedEvent event) {
        reload();

        String message;
        switch (event.result.status) {
            case UPDATED:
                message = getString(R.string.phone_block_update_result, event.result.size);
                break;

            case NOT_DUE:
                message = getString(R.string.phone_block_update_not_due, event.result.size);
                break;

            case NOT_CONFIGURED:
                return; // the source is switched off, there is nothing to say

            default:
                message = getString(R.string.phone_block_update_failed);
                break;
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void reload() {
        sources.clear();
        if (sourceService != null) sources.addAll(sourceService.getSources());

        adapter.notifyDataSetChanged();
    }

    /**
     * Adding a source starts from the ones the app knows, because their addresses are the
     * part nobody remembers: the community database it has always used, and the PhoneBlock
     * list. Anything else starts from an empty form.
     */
    public void onAddClicked(View view) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.source_add)
                .setItems(R.array.source_presets, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startActivity(EditNumberSourceActivity.getIntent(this,
                                    NumberSource.Type.DATABASE, DbManager.DEFAULT_URL,
                                    NumberSource.Auth.NONE));
                            break;

                        case 1:
                            startActivity(EditNumberSourceActivity.getIntent(this,
                                    NumberSource.Type.PHONE_BLOCK, PhoneBlockService.DEFAULT_URL,
                                    NumberSource.Auth.BEARER));
                            break;

                        default:
                            startActivity(EditNumberSourceActivity.getIntent(this, null));
                            break;
                    }
                })
                .setNegativeButton(R.string.back, null)
                .show();
    }

    /** What the row says about a source: its part in the database, how often, how it last went. */
    private String getStatus(NumberSource source) {
        List<String> parts = new ArrayList<>(3);

        parts.add(getRole(source));
        parts.add(getString(getUpdatesName(source.getUpdates())));

        if (source.getType() == NumberSource.Type.PHONE_BLOCK) {
            // the list keeps its own account of when it was fetched and how big it is
            parts.add(PhoneBlockHelper.getListStatus(this));
        } else if (!TextUtils.isEmpty(source.getLastResult())) {
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

    /**
     * What the source is in the database: the one it is built on, or which layer on top.
     *
     * <p>Only the sources that are switched on are counted, because only they are asked -
     * switching one off moves everything below it up.
     */
    private String getRole(NumberSource source) {
        if (source.getType() != NumberSource.Type.DATABASE || !source.isEnabled()) {
            return getString(getTypeName(source.getType()));
        }

        int layer = 0;
        for (NumberSource other : sources) {
            if (!other.isEnabled() || other.getType() != NumberSource.Type.DATABASE) continue;

            if (other.getId().equals(source.getId())) break;

            layer++;
        }

        return layer == 0
                ? getString(R.string.source_role_base)
                : getString(R.string.source_role_layer, layer);
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

    /**
     * Moving a row moves a source in the order the database is built in.
     *
     * <p>The list is written down when the finger comes off rather than at every step a drag
     * passes through, and the rows are drawn again afterwards: which source is the database
     * and which is a layer follows from where they sit.
     */
    private class ReorderCallback extends ItemTouchHelper.SimpleCallback {

        ReorderCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true; // besides the handle, which starts a drag straight away
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();

            if (from < 0 || to < 0 || from >= sources.size() || to >= sources.size()) {
                return false;
            }

            Collections.swap(sources, from, to);
            adapter.notifyItemMoved(from, to);

            reordered = true;

            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            // nothing is swiped away: a source is deleted where it is edited
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);

            if (!reordered) return;
            reordered = false;

            if (sourceService != null) sourceService.save(sources);

            adapter.notifyDataSetChanged();
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
            final Button testButton, fetchButton;
            final SwitchCompat enabledSwitch;
            final ImageView dragHandle;

            /** Kept, because binding a recycled row has to put it aside for a moment. */
            final CompoundButton.OnCheckedChangeListener enabledListener = (v, checked) -> {
                NumberSource source = getSource();
                if (source == null || source.isEnabled() == checked) return;

                source.setEnabled(checked);
                if (sourceService != null) sourceService.save(source);

                // switching one off moves every layer below it up a place
                v.post(() -> adapter.notifyDataSetChanged());
            };

            @SuppressLint("ClickableViewAccessibility") // the handle drags, it doesn't click
            ViewHolder(@NonNull View itemView) {
                super(itemView);

                name = itemView.findViewById(R.id.name);
                url = itemView.findViewById(R.id.url);
                status = itemView.findViewById(R.id.status);
                testButton = itemView.findViewById(R.id.testButton);
                fetchButton = itemView.findViewById(R.id.fetchButton);
                enabledSwitch = itemView.findViewById(R.id.enabledSwitch);
                dragHandle = itemView.findViewById(R.id.dragHandle);

                dragHandle.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        touchHelper.startDrag(this);
                    }
                    return false;
                });

                itemView.setOnClickListener(v -> {
                    NumberSource source = getSource();
                    if (source != null) {
                        startActivity(EditNumberSourceActivity.getIntent(
                                NumberSourcesActivity.this, source.getId()));
                    }
                });

                testButton.setOnClickListener(v -> {
                    NumberSource source = getSource();
                    if (source != null) test(source);
                });

                fetchButton.setOnClickListener(v -> {
                    NumberSource source = getSource();
                    if (source != null) fetch(source);
                });
            }

            void bind(NumberSource source) {
                name.setText(!TextUtils.isEmpty(source.getName())
                        ? source.getName() : getString(getTypeName(source.getType())));

                url.setText(source.getUrl());
                url.setVisibility(TextUtils.isEmpty(source.getUrl()) ? View.GONE : View.VISIBLE);

                status.setText(getStatus(source));

                // set without the listener: a recycled row would report a change that isn't one
                enabledSwitch.setOnCheckedChangeListener(null);
                enabledSwitch.setChecked(source.isEnabled());
                enabledSwitch.setOnCheckedChangeListener(enabledListener);

                /*
                 * A PhoneBlock list is fetched on its own. The database is downloaded and
                 * built in one go - it is built from every source that is switched on, so
                 * this row starts the same work the menu does, from where the user is.
                 */
                boolean canFetch = source.getType() == NumberSource.Type.PHONE_BLOCK
                        || source.getType() == NumberSource.Type.DATABASE;

                fetchButton.setVisibility(canFetch ? View.VISIBLE : View.GONE);
                fetchButton.setText(source.getType() == NumberSource.Type.DATABASE
                        ? R.string.source_download : R.string.source_fetch);

                testButton.setEnabled(true);
            }

            /** The source this row is showing right now, or null if the list moved on. */
            private NumberSource getSource() {
                int position = getBindingAdapterPosition();

                return position >= 0 && position < sources.size()
                        ? sources.get(position) : null;
            }

            /**
             * Asks the source for its numbers now, rather than waiting for the next day.
             *
             * <p>For the database that means downloading it and building it in one go: what
             * is downloaded is of no use until the layers are on it and the filter has run,
             * so there is no state in between to leave the user in.
             */
            private void fetch(NumberSource source) {
                boolean database = source.getType() == NumberSource.Type.DATABASE;

                Toast.makeText(NumberSourcesActivity.this,
                        database ? R.string.sources_compiling : R.string.source_fetching,
                        Toast.LENGTH_SHORT).show();

                TaskService.start(NumberSourcesActivity.this, database
                        ? TaskService.TASK_DOWNLOAD_MAIN_DB : TaskService.TASK_UPDATE_PHONE_BLOCK);
            }

            /**
             * Tries the source out and keeps what came of it: the row is the stored source,
             * so what the test found is worth writing down next to how the last fetch went.
             */
            private void test(NumberSource source) {
                testButton.setEnabled(false);
                status.setText(R.string.source_test_running);

                String secret = sourceService != null
                        ? sourceService.getSecret(source.getId()) : null;

                SourceTestHelper.test(NumberSourcesActivity.this, source, secret,
                        (result, message) -> {
                            if (isFinishing()) return;

                            source.setLastResult(message);
                            if (sourceService != null) sourceService.save(source);

                            adapter.notifyDataSetChanged();
                        });
            }
        }
    }

}
