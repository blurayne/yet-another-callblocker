package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.widget.Toast;

import dummydomain.yetanothercallblocker.data.source.ArchiveUtils;
import dummydomain.yetanothercallblocker.data.source.NumberSource;
import dummydomain.yetanothercallblocker.data.source.SourceTester;

/**
 * Trying a source out from a screen: the waiting happens elsewhere, the words happen here.
 *
 * <p>Both screens that show sources can ask - the list, for the source as it is stored, and
 * the editor, for the source as it is being typed - so what the answer is called lives in
 * one place rather than two.
 */
public class SourceTestHelper {

    /** Told what the test found, on the thread the test was started from. */
    public interface Callback {
        void onResult(SourceTester.Result result, String message);
    }

    private SourceTestHelper() {
    }

    /**
     * Goes to the source in the background and comes back with a sentence about it.
     *
     * @param secret the token or password, or null when the source needs none
     */
    public static void test(Context context, NumberSource source, String secret,
                            Callback callback) {
        Context appContext = context.getApplicationContext();

        Toast.makeText(appContext, R.string.source_test_running, Toast.LENGTH_SHORT).show();

        @SuppressLint("StaticFieldLeak") // the application context outlives the task
        AsyncTask<Void, Void, SourceTester.Result> task
                = new AsyncTask<Void, Void, SourceTester.Result>() {
            @Override
            protected SourceTester.Result doInBackground(Void... voids) {
                return SourceTester.test(source, secret);
            }

            @Override
            protected void onPostExecute(SourceTester.Result result) {
                String message = getMessage(appContext, result);

                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();

                if (callback != null) callback.onResult(result, message);
            }
        };

        task.execute();
    }

    /** What the test found, as it is shown in a row or under the fields. */
    public static String getMessage(Context context, SourceTester.Result result) {
        return context.getString(R.string.source_test_result, getOutcome(context, result));
    }

    private static String getOutcome(Context context, SourceTester.Result result) {
        switch (result.outcome) {
            case OK:
                return result.format != null
                        ? context.getString(R.string.source_test_ok_format,
                                context.getString(getFormatName(result.format)))
                        : context.getString(R.string.source_test_ok);

            case NO_URL:
                return context.getString(R.string.source_test_no_url);

            case BAD_URL:
                return context.getString(R.string.source_test_bad_url);

            case UNAUTHORIZED:
                return context.getString(R.string.source_test_unauthorized, result.code);

            case HTTP_ERROR:
                return context.getString(R.string.source_test_http_error, result.code);

            case EMPTY:
                return context.getString(R.string.source_test_empty);

            default:
                return context.getString(R.string.source_test_unreachable,
                        !TextUtils.isEmpty(result.detail) ? result.detail : "");
        }
    }

    private static int getFormatName(ArchiveUtils.Format format) {
        switch (format) {
            case GZIP: return R.string.source_format_gzip;
            case TAR: return R.string.source_format_tar;
            case TAR_GZIP: return R.string.source_format_tar_gzip;
            case ZIP: return R.string.source_format_zip;
            default: return R.string.source_format_plain;
        }
    }

}
