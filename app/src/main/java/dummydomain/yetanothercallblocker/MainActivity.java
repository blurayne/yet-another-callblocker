package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.ContactsContract;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.arch.core.util.Function;
import androidx.lifecycle.LiveData;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import dummydomain.yetanothercallblocker.data.CallLogDataSource;
import dummydomain.yetanothercallblocker.data.CallLogItem;
import dummydomain.yetanothercallblocker.data.CallLogItemGroup;
import dummydomain.yetanothercallblocker.data.NumberInfoCache;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.event.BlacklistChangedEvent;
import dummydomain.yetanothercallblocker.event.BlacklistItemChangedEvent;
import dummydomain.yetanothercallblocker.event.CallEndedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadingEvent;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdateFinished;
import dummydomain.yetanothercallblocker.event.WhitelistChangedEvent;
import dummydomain.yetanothercallblocker.work.TaskService;

public class MainActivity extends AppCompatActivity {

    private static final String STATE_CALL_LOG_DATA_LAST_KEY = "call_log_data_last_key";
    private static final String STATE_CALL_LOG_LAYOUT_MANAGER = "call_log_layout_manager";

    private final Settings settings = App.getSettings();

    private CallLogItemRecyclerViewAdapter callLogAdapter;
    private RecyclerView recyclerView;
    private CallLogDataSource.Factory callLogDsFactory;

    private Parcelable callLogLayoutManagerState;

    private Handler handler;

    /** Watches the contacts, so that a number that has just become one is shown as one. */
    private ContentObserver contactsObserver;

    private final Runnable refreshCallLogRunnable = this::refreshCallLog;

    private AsyncTask<Void, Void, Boolean> checkMainDbTask;

    private boolean activityFirstStart = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler = new Handler(getMainLooper());

        callLogAdapter = new CallLogItemRecyclerViewAdapter(this::onCallLogItemClicked);
        recyclerView = findViewById(R.id.callLogList);
        recyclerView.setAdapter(callLogAdapter);
        recyclerView.addItemDecoration(new CustomVerticalDivider(this));

        callLogDsFactory = new CallLogDataSource.Factory(getCallLogGroupConverter());

        PagedList.Config config = new PagedList.Config.Builder()
                .setPageSize(30)
                .setInitialLoadSizeHint(30)
                .setPrefetchDistance(15)
                .build();

        CallLogDataSource.GroupId initialKey = null;
        if (savedInstanceState != null) {
            initialKey = CallLogDataSource.GroupId.fromParcelable(
                    savedInstanceState.getParcelable(STATE_CALL_LOG_DATA_LAST_KEY));

            callLogLayoutManagerState = savedInstanceState
                    .getParcelable(STATE_CALL_LOG_LAYOUT_MANAGER);
        }

        LiveData<PagedList<CallLogItemGroup>> callLogData
                = new LivePagedListBuilder<>(callLogDsFactory, config)
                .setInitialLoadKey(initialKey)
                .build();

        callLogData.observe(this, data -> {
            callLogAdapter.submitList(data);

            if (callLogLayoutManagerState != null) {
                Objects.requireNonNull(recyclerView.getLayoutManager())
                        .onRestoreInstanceState(callLogLayoutManagerState);

                callLogLayoutManagerState = null;
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        PermissionHelper.handlePermissionsResult(this, requestCode, permissions, grantResults,
                settings.getIncomingCallNotifications(), settings.getCallBlockingEnabled(),
                settings.getUseContacts());

        updateCallLogVisibility();
        reloadCallLog();
    }

    @Override
    protected void onStart() {
        super.onStart();

        EventUtils.register(this);

        startCheckMainDbTask();

        checkPermissions();

        registerContactsObserver();

        updateCallLogVisibility();
        if (activityFirstStart) {
            activityFirstStart = false;
        } else {
            callLogDsFactory.setGroupConverter(getCallLogGroupConverter());

            // the lists and the contacts may have changed while the app was away
            refreshCallLog();
        }
    }

    @Override
    protected void onStop() {
        EventUtils.unregister(this);

        unregisterContactsObserver();

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelCheckMainDbTask();

        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        PagedList<CallLogItemGroup> currentList = callLogAdapter.getCurrentList();
        if (currentList != null) {
            Object lastKey = currentList.getLastKey();
            if (lastKey != null) {
                outState.putParcelable(STATE_CALL_LOG_DATA_LAST_KEY,
                        ((CallLogDataSource.GroupId) lastKey).saveInstanceState());
            }
        }

        outState.putParcelable(STATE_CALL_LOG_LAYOUT_MANAGER,
                Objects.requireNonNull(recyclerView.getLayoutManager()).onSaveInstanceState());
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onCallEvent(CallEndedEvent event) {
        new Handler(getMainLooper()).postDelayed(this::reloadCallLog, 1000);
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onBlacklistChanged(BlacklistChangedEvent event) {
        refreshCallLog();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onBlacklistItemChanged(BlacklistItemChangedEvent event) {
        // an entry can be edited into a pattern that covers other numbers in the log, too
        refreshCallLog();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onWhitelistChanged(WhitelistChangedEvent event) {
        refreshCallLog();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloadFinished(MainDbDownloadFinishedEvent event) {
        reloadCallLog();

        // an empty list of sources means nothing was fetched, which is worth saying out loud
        if (event.noSources) showNoSourcesDialog();
    }

    private void showNoSourcesDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.no_db_sources_title)
                .setMessage(R.string.no_db_sources_text)
                .setPositiveButton(R.string.title_sources_activity,
                        (d, w) -> startActivity(NumberSourcesActivity.getIntent(this)))
                .setNegativeButton(R.string.back, null)
                .show();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdateFinished(SecondaryDbUpdateFinished event) {
        if (event.updated) reloadCallLog();
    }

    private void checkPermissions() {
        PermissionHelper.checkPermissions(this,
                settings.getIncomingCallNotifications(), settings.getCallBlockingEnabled(),
                settings.getUseContacts());
    }

    private void startCheckMainDbTask() {
        cancelCheckMainDbTask();
        @SuppressLint("StaticFieldLeak")
        AsyncTask<Void, Void, Boolean> checkMainDbTask = this.checkMainDbTask
                = new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                return YacbHolder.getCommunityDatabase().isOperational();
            }

            @Override
            protected void onPostExecute(Boolean result) {
                if (!result && EventUtils.bus().getStickyEvent(MainDbDownloadingEvent.class) == null) {
                    showNoMainDbDialog();
                }
            }
        };
        checkMainDbTask.execute();
    }

    private void cancelCheckMainDbTask() {
        if (checkMainDbTask != null) {
            checkMainDbTask.cancel(true);
            checkMainDbTask = null;
        }
    }

    private void showNoMainDbDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.no_main_db_title)
                .setMessage(R.string.no_main_db_text)
                .setPositiveButton(R.string.download_main_db,
                        (d, w) -> downloadMainDb())
                .setNegativeButton(R.string.no, null)
                .show();
    }

    public void downloadMainDb() {
        TaskService.start(this, TaskService.TASK_DOWNLOAD_MAIN_DB);
    }

    public void onLookupNumberClicked(MenuItem item) {
        startActivity(new Intent(this, LookupNumberActivity.class));
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // the row both starts and ends a pause, so it says which of the two it would do
        menu.findItem(R.id.menu_pause_blocking).setTitle(
                BlockingPauseHelper.getActionTitle(this));

        return super.onPrepareOptionsMenu(menu);
    }

    public void onPauseBlockingClicked(MenuItem item) {
        BlockingPauseHelper.show(this, this::invalidateOptionsMenu);
    }

    public void onOpenBlacklist(MenuItem item) {
        startActivity(BlacklistActivity.getIntent(this));
    }

    public void onOpenWhitelist(MenuItem item) {
        startActivity(WhitelistActivity.getIntent(this));
    }

    public void onOpenSettings(MenuItem item) {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    public void onOpenAbout(MenuItem item) {
        startActivity(new Intent(this, AboutActivity.class));
    }

    private void onCallLogItemClicked(CallLogItemGroup group) {
        List<CallLogItem> items = group.getItems();

        InfoDialogHelper.showDialog(this, items.get(0).numberInfo, getCallInfo(items), null);
    }

    /** The list shows how long ago a call was; the dialog says exactly when it was. */
    private CharSequence getCallInfo(List<CallLogItem> items) {
        // the newest call of the group comes first
        String time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                .format(new Date(items.get(0).timestamp));

        return items.size() > 1
                ? getString(R.string.info_call_time_last, items.size(), time)
                : getString(R.string.info_call_time, time);
    }

    private void reloadCallLog() {
        callLogDsFactory.invalidate();
    }

    /**
     * Reloads the call log with what is known about the numbers looked up again.
     *
     * <p>The rows are built from the contacts and the lists, so adding a contact or blacklisting
     * a number changes what they should show - the info kept from the last call is dropped for
     * that reason.
     */
    private void refreshCallLog() {
        NumberInfoCache cache = YacbHolder.getNumberInfoCache();
        if (cache != null) cache.clear();

        reloadCallLog();
    }

    private void registerContactsObserver() {
        if (contactsObserver != null || !PermissionHelper.hasContactsPermission(this)) return;

        contactsObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                // a sync can report many changes in a row; one reload is enough for all of them
                handler.removeCallbacks(refreshCallLogRunnable);
                handler.postDelayed(refreshCallLogRunnable, 500);
            }
        };

        try {
            getContentResolver().registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI, true, contactsObserver);
        } catch (Exception e) { // the permission may have been taken away in the meantime
            contactsObserver = null;
        }
    }

    private void unregisterContactsObserver() {
        if (contactsObserver == null) return;

        handler.removeCallbacks(refreshCallLogRunnable);

        try {
            getContentResolver().unregisterContentObserver(contactsObserver);
        } catch (Exception ignored) {
        }

        contactsObserver = null;
    }

    private void updateCallLogVisibility() {
        setCallLogVisibility(PermissionHelper.hasCallLogPermission(this));
    }

    private void setCallLogVisibility(boolean visible) {
        findViewById(R.id.callLogPermissionMessage)
                .setVisibility(visible ? View.GONE : View.VISIBLE);

        findViewById(R.id.callLogList).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private Function<List<CallLogItem>, List<CallLogItemGroup>> getCallLogGroupConverter() {
        Function<List<CallLogItem>, List<CallLogItemGroup>> converter;
        switch (settings.getCallLogGrouping()) {
            case Settings.PREF_CALL_LOG_GROUPING_NONE:
                converter = CallLogItemGroup::noGrouping;
                break;
            case Settings.PREF_CALL_LOG_GROUPING_DAY:
                converter = CallLogItemGroup::groupInDay;
                break;
            default:
                converter = CallLogItemGroup::groupConsecutive;
                break;
        }
        return converter;
    }

}
