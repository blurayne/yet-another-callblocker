package net.evolution515.callblocker;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import net.evolution515.callblocker.data.YacbHolder;
import net.evolution515.callblocker.event.MainDbDownloadFinishedEvent;
import net.evolution515.callblocker.event.SecondaryDbUpdateFinished;
import net.evolution515.callblocker.event.SecondaryDbUpdatingEvent;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import net.evolution515.callblocker.work.TaskService;

public class AboutActivity extends AppCompatActivity {

    private final Settings settings = App.getSettings();
    private final CommunityDatabase communityDatabase = YacbHolder.getCommunityDatabase();

    private TextView dbInfoTv;

    private boolean checkingForUpdates;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        setLink(R.id.homepage, R.string.url_repo, R.string.homepage);
        setLink(R.id.faq, R.string.url_faq, R.string.faq);
        setLink(R.id.translate, R.string.url_translate, R.string.translate);
        setLink(R.id.issues, R.string.url_issues, R.string.issues);

        // the projects the numbers come from, which are other people's work and other people's bills
        setLink(R.id.about_sia, R.string.url_sia_about, R.string.about_sia);
        setLink(R.id.about_phone_block, R.string.url_phone_block, R.string.about_phone_block);
        setLink(R.id.about_tellows, R.string.url_tellows, R.string.about_tellows);

        // where a number is from comes out of libphonenumber, whose licence asks to be named
        setLink(R.id.about_geo, R.string.url_libphonenumber, R.string.about_geo);

        ((TextView) findViewById(R.id.app_version)).setText(getString(R.string.version_string,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        /*
         * Which build this actually is. A screenshot of something that was fixed an hour ago
         * costs an evening, and the answer is the revision it was built from, the branch it
         * came off, and when - the plus after the revision means the tree had changes in it.
         */
        ((TextView) findViewById(R.id.build_info)).setText(
                getString(R.string.build_info, BuildConfig.BUILD_TYPE,
                        BuildConfig.GIT_BRANCH, BuildConfig.GIT_REVISION)
                        + "\n" + getString(R.string.build_time, BuildConfig.BUILD_TIME));

        dbInfoTv = findViewById(R.id.db_info);

        dbInfoTv.setOnLongClickListener(this::onDbInfoLongClicked);

        if (EventUtils.bus().getStickyEvent(SecondaryDbUpdatingEvent.class) != null) {
            checkingForUpdates = true;
        }

        updateDbInfo();
    }

    @Override
    protected void onStart() {
        super.onStart();

        EventUtils.register(this);
    }

    @Override
    protected void onStop() {
        EventUtils.unregister(this);

        super.onStop();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloadFinished(MainDbDownloadFinishedEvent event) {
        updateDbInfo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdating(SecondaryDbUpdatingEvent event) {
        checkingForUpdates = true;
        updateDbInfo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdateFinished(SecondaryDbUpdateFinished event) {
        checkingForUpdates = false;
        updateDbInfo();
    }

    private void updateDbInfo() {
        // TODO: async?

        boolean clickable;

        String dbVersionValue;
        if (communityDatabase.isOperational()) {
            dbVersionValue = String.valueOf(communityDatabase.getEffectiveDbVersion());
            clickable = true;
        } else {
            dbVersionValue = getString(R.string.db_version_not_available);
            clickable = false;
        }

        if (clickable && checkingForUpdates) clickable = false;

        setUpdateClickable(clickable);

        String lastCheckValue;
        if (checkingForUpdates) {
            lastCheckValue = getString(R.string.db_last_update_check_checking);
        } else {
            long lastUpdateCheckTime = settings.getLastUpdateCheckTime();
            lastCheckValue = lastUpdateCheckTime != 0 ?
                    DateUtils.getRelativeTimeSpanString(lastUpdateCheckTime).toString()
                    : getString(R.string.db_last_update_check_never);
        }

        String dbInfoString = getString(R.string.db_version, dbVersionValue)
                + "\n" + getString(R.string.db_last_update_check, lastCheckValue);

        dbInfoTv.setText(dbInfoString);
    }

    private void setUpdateClickable(boolean clickable) {
        dbInfoTv.setClickable(clickable);
    }

    public void onUpdateDbClicked(View view) {
        setUpdateClickable(false);

        TaskService.start(this, TaskService.TASK_UPDATE_SECONDARY_DB);
    }

    /** The database has a screen of its own in the settings now; this is the short way to it. */
    private boolean onDbInfoLongClicked(View view) {
        startActivity(new Intent(this, SettingsActivity.class));
        finish();
        return true;
    }

    private void setLink(@IdRes int textView, @StringRes int url, @StringRes int text) {
        setLink(findViewById(textView), getString(url), getString(text));
    }

    private void setLink(TextView textView, String url, String text) {
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setText(fromHtml("<a href=\"" + url + "\">" + text + "</a>"));
    }

    private static Spanned fromHtml(String s) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY);
        } else {
            return fromHtmlLegacy(s);
        }
    }

    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    private static Spanned fromHtmlLegacy(String s) {
        return Html.fromHtml(s);
    }

}
