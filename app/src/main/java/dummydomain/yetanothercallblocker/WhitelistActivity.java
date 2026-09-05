package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import dummydomain.yetanothercallblocker.data.WhitelistService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.WhitelistChangedEvent;

/**
 * The whitelist: the numbers that are never blocked, one row each, sorted.
 *
 * <p>A row is tapped to change it and has its own remove button, since the list is short
 * enough that selecting several at a time isn't worth the extra mode.
 */
public class WhitelistActivity extends AppCompatActivity {

    private final WhitelistService whitelistService = YacbHolder.getWhitelistService();

    private WhitelistAdapter adapter;
    private View emptyView;

    public static Intent getIntent(Context context) {
        return new Intent(context, WhitelistActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whitelist);

        adapter = new WhitelistAdapter(this::onItemClicked, this::onRemoveClicked);

        RecyclerView recyclerView = findViewById(R.id.whitelistItemsList);
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(new CustomVerticalDivider(this));

        emptyView = findViewById(R.id.empty);

        reloadItems();
    }

    @Override
    protected void onStart() {
        super.onStart();

        EventUtils.register(this);

        reloadItems(); // may have changed from a dialog elsewhere
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
        List<String> entries = whitelistService.getEntries();

        adapter.setEntries(entries);
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    public void onAddClicked(View view) {
        WhitelistDialogHelper.showAddDialog(this, null, null);
    }

    private void onItemClicked(String entry) {
        WhitelistDialogHelper.showEditDialog(this, entry, null);
    }

    private void onRemoveClicked(String entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.are_you_sure)
                .setMessage(getString(R.string.whitelist_delete_confirmation, entry))
                .setPositiveButton(R.string.yes, (dialog, which) -> whitelistService.remove(entry))
                .setNegativeButton(R.string.no, null)
                .show();
    }

}
