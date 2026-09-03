package dummydomain.yetanothercallblocker.data;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.BuildConfig;
import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.utils.DeferredInit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Keeps the local copy of the PhoneBlock community list up to date.
 *
 * <p>The whole list is fetched once and then followed with the changes since the version that
 * was fetched, which is what the API is meant to be used like: it asks for a full download at
 * most once a month, changes at most once a day, and for the times to be spread out rather than
 * every client asking at the same hour.
 */
public class PhoneBlockService {

    /** What an update did. */
    public enum Status {
        UPDATED, NOT_DUE, NOT_CONFIGURED, FAILED
    }

    public static class Result {

        public final Status status;
        public final int size;

        Result(Status status, int size) {
            this.status = status;
            this.size = size;
        }

    }

    public static final String DEFAULT_URL = "https://phoneblock.net/phoneblock/api/blocklist";

    /** The API asks for changes to be fetched at most daily, at times that aren't all the same. */
    private static final long UPDATE_INTERVAL = TimeUnit.HOURS.toMillis(23);
    private static final long UPDATE_INTERVAL_SPREAD = TimeUnit.HOURS.toMillis(2);

    /** The API asks for the whole list at most once a month. */
    private static final long FULL_UPDATE_INTERVAL = TimeUnit.DAYS.toMillis(31);

    private static final int MAX_ENTRIES = 2_000_000; // a sane limit for one response

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockService.class);

    private static final Random RANDOM = new Random();

    private final Settings settings;
    private final PhoneBlockList list;

    public PhoneBlockService(Settings settings, PhoneBlockList list) {
        this.settings = settings;
        this.list = list;
    }

    /** Whether an update would be fetched now. */
    public boolean isUpdateDue() {
        long due = settings.getPhoneBlockNextUpdateTime();
        return due <= 0 || System.currentTimeMillis() >= due;
    }

    /**
     * Fetches what is missing: the whole list when there is none, the changes otherwise.
     *
     * @param force whether to fetch even if the last update was recent
     */
    public Result update(boolean force) {
        LOG.debug("update({})", force);

        if (!settings.getUsePhoneBlock()) {
            LOG.debug("update() PhoneBlock is turned off");
            return new Result(Status.NOT_CONFIGURED, 0);
        }

        boolean full = list.isEmpty() || list.getListVersion() <= 0;

        if (full && !isFullUpdateDue()) {
            // asking for the whole list again straight away is what the API asks clients not to do
            LOG.info("update() the whole list was fetched recently, not fetching it again");
            return new Result(Status.NOT_DUE, list.getSize());
        }

        if (!force && !isUpdateDue()) {
            LOG.debug("update() not due yet");
            return new Result(Status.NOT_DUE, list.getSize());
        }

        try {
            Result result = fetch(full);

            if (result.status == Status.UPDATED) {
                long now = System.currentTimeMillis();

                settings.setPhoneBlockLastUpdateTime(now);
                // spread out when this device asks next, so that clients don't arrive in a herd
                settings.setPhoneBlockNextUpdateTime(now + UPDATE_INTERVAL
                        + (long) (RANDOM.nextDouble() * UPDATE_INTERVAL_SPREAD));

                if (full) settings.setPhoneBlockLastFullUpdateTime(now);
            }

            return result;
        } catch (Exception e) {
            LOG.error("update() failed", e);
            return new Result(Status.FAILED, list.getSize());
        }
    }

    private boolean isFullUpdateDue() {
        long last = settings.getPhoneBlockLastFullUpdateTime();
        return last <= 0 || System.currentTimeMillis() - last >= FULL_UPDATE_INTERVAL;
    }

    private Result fetch(boolean full) throws IOException {
        HttpUrl url = HttpUrl.parse(settings.getPhoneBlockUrl());
        if (url == null) {
            LOG.warn("fetch() the URL can't be used");
            return new Result(Status.NOT_CONFIGURED, list.getSize());
        }

        HttpUrl.Builder urlBuilder = url.newBuilder().addQueryParameter("format", "json");
        if (!full) {
            urlBuilder.addQueryParameter("since", String.valueOf(list.getListVersion()));
        }

        Request.Builder requestBuilder = new Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "YetAnotherCallBlocker/" + BuildConfig.VERSION_NAME);

        String token = settings.getPhoneBlockToken();
        if (!TextUtils.isEmpty(token)) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        DeferredInit.initNetwork();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS) // the whole list takes a while
                .build();

        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                LOG.warn("fetch() the server answered {}", response.code());
                return new Result(Status.FAILED, list.getSize());
            }

            ResponseBody body = response.body();
            if (body == null) return new Result(Status.FAILED, list.getSize());

            return apply(body.string(), full);
        }
    }

    private Result apply(String json, boolean full) throws IOException {
        List<PhoneBlockList.Entry> entries = new ArrayList<>();
        int version;

        try {
            JSONObject root = new JSONObject(json);

            version = root.optInt("version", list.getListVersion());

            JSONArray numbers = root.optJSONArray("numbers");
            int count = numbers != null ? Math.min(numbers.length(), MAX_ENTRIES) : 0;

            for (int i = 0; i < count; i++) {
                JSONObject entry = numbers.optJSONObject(i);
                if (entry == null) continue;

                long number = parseNumber(entry.optString("phone"));
                if (number <= 0) continue;

                entries.add(new PhoneBlockList.Entry(number,
                        PhoneBlockList.Rating.parse(entry.optString("rating"))));
            }
        } catch (Exception e) {
            LOG.error("apply() couldn't read the answer", e);
            return new Result(Status.FAILED, list.getSize());
        }

        list.apply(entries, version, full);

        LOG.info("apply() the list holds {} numbers", list.getSize());
        return new Result(Status.UPDATED, list.getSize());
    }

    /** Turns {@code +49123456789} into a number that can be compared and stored. */
    static long parseNumber(String phone) {
        if (TextUtils.isEmpty(phone)) return 0;

        long number = 0;

        for (int i = 0; i < phone.length(); i++) {
            char c = phone.charAt(i);

            if (c >= '0' && c <= '9') {
                if (number > Long.MAX_VALUE / 10 - 9) return 0; // longer than any phone number
                number = number * 10 + (c - '0');
            } else if (c != '+' && c != ' ' && c != '-' && c != '/' && c != '(' && c != ')') {
                return 0; // not a number at all
            }
        }

        return number;
    }

}
