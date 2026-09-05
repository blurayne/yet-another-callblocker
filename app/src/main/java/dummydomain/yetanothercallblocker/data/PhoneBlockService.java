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
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
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

    /** What reporting a number did. */
    public enum ReportStatus {
        /** The community has the report. */
        REPORTED,
        /** Reporting needs an account, and no token is set. */
        NO_TOKEN,
        /** The token wasn't accepted (it was revoked, or it is mistyped). */
        UNAUTHORIZED,
        /** The server refused the report (an unusable number, or too many reports). */
        REJECTED,
        FAILED
    }

    /** What checking the token found. */
    public enum TokenStatus {
        /** The server knows the token. */
        OK,
        /** There's no token to check. */
        NO_TOKEN,
        /** The token was checked recently. */
        NOT_DUE,
        /** The server doesn't accept the token any more. */
        INVALID,
        /** The check didn't get through - which says nothing about the token. */
        FAILED
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

    /** The token is checked about once a day, so that a revoked one doesn't go unnoticed. */
    private static final long TOKEN_CHECK_INTERVAL = TimeUnit.HOURS.toMillis(23);

    /** What the API accepts as a comment. */
    public static final int MAX_COMMENT_LENGTH = 1000;

    private static final String ENDPOINT_RATE = "rate";
    private static final String ENDPOINT_TEST_CONNECT = "test-connect";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

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

        Request.Builder requestBuilder = newRequest(urlBuilder.build());

        OkHttpClient client = newClient(120); // the whole list takes a while

        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                LOG.warn("fetch() the server answered {}", response.code());

                if (isUnauthorized(response.code())) noteTokenRejected();

                return new Result(Status.FAILED, list.getSize());
            }

            noteTokenAccepted();

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

    /**
     * Reports a number to the community.
     *
     * <p>This is the one thing that tells PhoneBlock about a call: the number, what it was, and
     * the comment the user wrote are sent to the server. It needs an account, because a report
     * counts as a vote.
     */
    public ReportStatus report(String number, PhoneBlockList.Rating rating, String comment) {
        LOG.debug("report({}, {})", rating, comment != null ? comment.length() + " chars" : null);

        if (TextUtils.isEmpty(number) || rating == null || rating.getApiName() == null) {
            return ReportStatus.FAILED;
        }

        if (TextUtils.isEmpty(settings.getPhoneBlockToken())) return ReportStatus.NO_TOKEN;

        HttpUrl url = apiUrl(ENDPOINT_RATE);
        if (url == null) return ReportStatus.FAILED;

        String body;
        try {
            JSONObject json = new JSONObject();
            json.put("phone", number);
            json.put("rating", rating.getApiName());
            if (!TextUtils.isEmpty(comment)) {
                json.put("comment", comment.length() > MAX_COMMENT_LENGTH
                        ? comment.substring(0, MAX_COMMENT_LENGTH) : comment);
            }
            body = json.toString();
        } catch (Exception e) {
            LOG.error("report() couldn't build the report", e);
            return ReportStatus.FAILED;
        }

        Request request = newRequest(url)
                .post(RequestBody.create(JSON, body))
                .build();

        try (Response response = newClient(60).newCall(request).execute()) {
            int code = response.code();

            if (response.isSuccessful()) {
                noteTokenAccepted();

                LOG.info("report() the report was accepted");
                return ReportStatus.REPORTED;
            }

            LOG.warn("report() the server answered {}", code);

            if (isUnauthorized(code)) {
                noteTokenRejected();
                return ReportStatus.UNAUTHORIZED;
            }

            // the server has an opinion about the report itself rather than about the connection
            return code >= 400 && code < 500 ? ReportStatus.REJECTED : ReportStatus.FAILED;
        } catch (Exception e) {
            LOG.error("report() failed", e);
            return ReportStatus.FAILED;
        }
    }

    /** Whether reporting is possible: it's turned on and there's a token to report with. */
    public boolean canReport() {
        return settings.getUsePhoneBlock() && !TextUtils.isEmpty(settings.getPhoneBlockToken());
    }

    /** Checks the token unless it was checked within the last day. */
    public TokenStatus checkTokenIfDue() {
        if (!canReport()) return TokenStatus.NO_TOKEN;

        long last = settings.getPhoneBlockLastTokenCheckTime();
        if (last > 0 && Math.abs(System.currentTimeMillis() - last) < TOKEN_CHECK_INTERVAL) {
            return TokenStatus.NOT_DUE;
        }

        return checkToken();
    }

    /**
     * Asks the server whether it still knows the token.
     *
     * <p>A token that isn't accepted any more only shows up when it is used, and the list is
     * downloaded rarely enough that a revoked token could go unnoticed for a month.
     */
    public TokenStatus checkToken() {
        LOG.debug("checkToken()");

        if (TextUtils.isEmpty(settings.getPhoneBlockToken())) return TokenStatus.NO_TOKEN;

        HttpUrl url = apiUrl(ENDPOINT_TEST_CONNECT);
        if (url == null) return TokenStatus.FAILED;

        try (Response response = newClient(30).newCall(newRequest(url).build()).execute()) {
            if (response.isSuccessful()) {
                noteTokenAccepted();

                LOG.debug("checkToken() the token is accepted");
                return TokenStatus.OK;
            }

            LOG.warn("checkToken() the server answered {}", response.code());

            if (isUnauthorized(response.code())) {
                noteTokenRejected();
                return TokenStatus.INVALID;
            }

            // anything else is the server's problem, not the token's
            return TokenStatus.FAILED;
        } catch (Exception e) {
            LOG.warn("checkToken() failed", e);
            return TokenStatus.FAILED;
        }
    }

    /** An endpoint next to the one the list is downloaded from. */
    private HttpUrl apiUrl(String endpoint) {
        HttpUrl url = HttpUrl.parse(settings.getPhoneBlockUrl());
        if (url == null) {
            LOG.warn("apiUrl() the URL can't be used");
            return null;
        }

        HttpUrl.Builder builder = url.newBuilder().query(null);

        int segments = url.pathSegments().size();
        if (segments > 0) builder.removePathSegment(segments - 1); // the "blocklist" part

        return builder.addPathSegment(endpoint).build();
    }

    private Request.Builder newRequest(HttpUrl url) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", "YetAnotherCallBlocker/" + BuildConfig.VERSION_NAME);

        String token = settings.getPhoneBlockToken();
        if (!TextUtils.isEmpty(token)) builder.header("Authorization", "Bearer " + token);

        return builder;
    }

    private static OkHttpClient newClient(long readTimeoutSeconds) {
        DeferredInit.initNetwork();

        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    private static boolean isUnauthorized(int code) {
        return code == 401 || code == 403;
    }

    private void noteTokenAccepted() {
        if (TextUtils.isEmpty(settings.getPhoneBlockToken())) return;

        settings.setPhoneBlockLastTokenCheckTime(System.currentTimeMillis());
        if (!settings.getPhoneBlockTokenValid()) settings.setPhoneBlockTokenValid(true);
    }

    private void noteTokenRejected() {
        if (TextUtils.isEmpty(settings.getPhoneBlockToken())) return;

        LOG.warn("noteTokenRejected() the token isn't accepted any more");

        settings.setPhoneBlockLastTokenCheckTime(System.currentTimeMillis());
        settings.setPhoneBlockTokenValid(false);
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
