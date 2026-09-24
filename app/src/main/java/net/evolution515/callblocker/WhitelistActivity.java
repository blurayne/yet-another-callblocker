package net.evolution515.callblocker;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.selection.StorageStrategy;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import net.evolution515.callblocker.data.WhitelistItem;
import net.evolution515.callblocker.data.WhitelistService;
import net.evolution515.callblocker.data.YacbHolder;
import net.evolution515.callblocker.event.WhitelistChangedEvent;

/**
 * The whitelist: the numbers that are never blocked, one row each.
 *
 * <p>It works the way the blacklist screen does - a row is tapped to edit it, held to select
 * several and delete them at once, and the button adds one.
 */
public class WhitelistActivity extends AppCompatActivity {

    private final WhitelistService whitelistService = YacbHolder.getWhitelistService();

    private WhitelistAdapter adapter;
    private View emptyView;

    private SelectionTracker<String> selectionTracker;
    private ActionMode.Callback actionModeCallback;
    private ActionMode actionMode;

    public static Intent getIntent(Context context) {
        return new Intent(context, WhitelistActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whitelist);

        adapter = new WhitelistAdapter(this::onItemClicked);

        RecyclerView recyclerView = findViewById(R.id.whitelistItemsList);
        recyclerView.setAdapter(adapter);
        UiUtils.speedUpAnimations(recyclerView);
        recyclerView.addItemDecoration(new CustomVerticalDivider(this));

        emptyView = findViewById(R.id.empty);

        selectionTracker = new SelectionTracker.Builder<>(
                "whitelistSelection", recyclerView,
                adapter.getItemKeyProvider(),
                adapter.getItemDetailsLookup(recyclerView),
                StorageStrategy.createStringStorage())
                .build();

        adapter.setSelectionTracker(selectionTracker);

        actionModeCallback = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                mode.getMenuInflater().inflate(R.menu.activity_whitelist_action_mode, menu);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == R.id.menu_select_all) {
                    selectionTracker.setItemsSelected(adapter.getAllKeys(), true);
                    return true;
                } else if (item.getItemId() == R.id.menu_delete) {
                    new AlertDialog.Builder(WhitelistActivity.this)
                            .setTitle(R.string.are_you_sure)
                            .setMessage(R.string.whitelist_delete_confirmation)
                            .setPositiveButton(R.string.yes, (dialog, which) -> {
                                if (selectionTracker.hasSelection()) {
                                    List<String> patterns = new ArrayList<>();
                                    for (String pattern : selectionTracker.getSelection()) {
                                        patterns.add(pattern);
                                    }

                                    selectionTracker.clearSelection();
                                    whitelistService.remove(patterns);
                                }
                            })
                            .setNegativeButton(R.string.no, null)
                            .show();
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                selectionTracker.clearSelection();
                actionMode = null;
            }
        };

        selectionTracker.addObserver(new SelectionTracker.SelectionObserver<String>() {
            @Override
            public void onItemStateChanged(@NonNull String key, boolean selected) {
                if (selectionTracker.hasSelection()) {
                    if (actionMode == null) {
                        actionMode = startSupportActionMode(actionModeCallback);
                    }
                } else if (actionMode != null) {
                    actionMode.finish();
                    actionMode = null;
                }

                if (actionMode != null) {
                    int count = selectionTracker.getSelection().size();
                    actionMode.setTitle(getResources().getQuantityString(
                            R.plurals.selected_count, count, count));
                }
            }
        });

        selectionTracker.onRestoreInstanceState(savedInstanceState);

        reloadItems();
    }

    @Override
    protected void onStart() {
        super.onStart();

        EventUtils.register(this);

        reloadItems(); // may have been changed elsewhere
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        selectionTracker.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        EventUtils.unregister(this);

        super.onStop();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onWhitelistChanged(WhitelistChangedEvent event) {
        reloadItems();
    }

    private void reloadItems() {
        List<WhitelistItem> items = whitelistService.getItems();

        adapter.setItems(items);
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    public void onAddClicked(View view) {
        startActivity(EditWhitelistItemActivity.getIntent(this, null, null));
    }

    private void onItemClicked(WhitelistItem item) {
        startActivity(EditWhitelistItemActivity.getEditIntent(this, item.getPattern()));
    }

}
