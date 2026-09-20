package dummydomain.yetanothercallblocker.data.source;

import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.BuildConfig;
import dummydomain.yetanothercallblocker.data.PhoneBlockList;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.utils.DeferredInit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Asking a source whether it is there, before it is asked for numbers.
 *
 * <p>An address that is typed wrong, a token that was revoked or a server that has moved
 * otherwise only shows up much later, at the next update, as a line in the log. This walks
 * the same way in a couple of seconds: it goes to the address the source names, with the
 * login the source carries, and looks at the beginning of what comes back.
 *
 * <p>Nothing is downloaded whole - for a database only the first few kilobytes are read, far
 * enough to tell a database from a web page saying "not found", and to name how it is packed.
 * Nothing is stored either: what the test found is handed back and left to the caller.
 */
public class SourceTester {

    private static final Logger LOG = LoggerFactory.getLogger(SourceTester.class);

    /** Enough to recognize what the answer is; a tar header alone takes 512 bytes. */
    private static final int PEEK_SIZE = 8 * 1024;

    private static final int CONNECT_TIMEOUT_SECONDS = 20;
    private static final int READ_TIMEOUT_SECONDS = 20;

    /** What a CardDAV server is asked, which is the least it has to understand. */
    private static final String PROPFIND_BODY
            = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>";

    /** Multi-Status: what a CardDAV server answers when it understood the question. */
    private static final int MULTI_STATUS = 207;

    /** How the test went. */
    public enum Outcome {
        /** The source answered, and what it said makes sense. */
        OK,
        /** There is no address to go to. */
        NO_URL,
        /** The address isn't one that can be used. */
        BAD_URL,
        /** The server didn't accept the login. */
        UNAUTHORIZED,
        /** The server answered, but not with what was asked for. */
        HTTP_ERROR,
        /** Nothing answered: no network, no such host, no way in. */
        UNREACHABLE,
        /** The server said yes and sent nothing. */
        EMPTY
    }

    /** What the test found, in the pieces the screens need to say it. */
    public static class Result {

        public final Outcome outcome;
        /** What the server answered, or 0 when it never got that far. */
        public final int code;
        /** How the data is packed, when something was read. */
        public final ArchiveUtils.Format format;
        /** Why it didn't work, in the words of whatever failed. */
        public final String detail;

        Result(Outcome outcome, int code, ArchiveUtils.Format format, String detail) {
            this.outcome = outcome;
            this.code = code;
            this.format = format;
            this.detail = detail;
        }

        public boolean isOk() {
            return outcome == Outcome.OK;
        }

    }

    private SourceTester() {
    }

    /**
     * Goes to the source and comes back with what happened. Talks to the network, so it
     * belongs on a background thread.
     *
     * @param secret the token or password, or null when the source needs none
     */
    public static Result test(NumberSource source, String secret) {
        if (source == null) return new Result(Outcome.NO_URL, 0, null, null);

        LOG.debug("test() {}", source.getType());

        if (TextUtils.isEmpty(source.getUrl())) {
            return new Result(Outcome.NO_URL, 0, null, null);
        }

        switch (source.getType()) {
            case PHONE_BLOCK:
                return testPhoneBlock(source, secret);

            case CARDDAV:
                return testCardDav(source, secret);

            default:
                return testDatabase(source, secret);
        }
    }

    /** Fetches the beginning of the database and says what arrived. */
    private static Result testDatabase(NumberSource source, String secret) {
        HttpUrl url = HttpUrl.parse(source.getUrl());
        if (url == null) return new Result(Outcome.BAD_URL, 0, null, null);

        // asking for the first bytes only; a server that can't is answered by ignoring it
        Request request = newRequest(url, source, secret)
                .header("Range", "bytes=0-" + (PEEK_SIZE - 1))
                // packed in flight as well as on disk would say nothing about the file
                .header("Accept-Encoding", "identity")
                .build();

        try (Response response = newClient().newCall(request).execute()) {
            Result failure = checkResponse(response);
            if (failure != null) return failure;

            ResponseBody body = response.body();
            if (body == null) return new Result(Outcome.EMPTY, response.code(), null, null);

            ArchiveUtils.Format format = peek(body.byteStream());
            if (format == null) return new Result(Outcome.EMPTY, response.code(), null, null);

            return new Result(Outcome.OK, response.code(), format, null);
        } catch (Exception e) {
            return failed(e);
        }
    }

    /**
     * Asks PhoneBlock for its list, the way the app asks for it.
     *
     * <p>It used to ask the endpoint that only answers about the token, which is a different
     * question at a different address - and one that can fail while fetching the list works
     * perfectly well. A test that takes another path than the thing it is testing is worth
     * nothing, so this one sends the same address, the same parameters and the same token.
     *
     * <p>"since" keeps the answer small: everything the list has learned since what is
     * already on the phone, which after the first fetch is next to nothing.
     */
    private static Result testPhoneBlock(NumberSource source, String secret) {
        HttpUrl url = HttpUrl.parse(source.getUrl());
        if (url == null) return new Result(Outcome.BAD_URL, 0, null, null);

        HttpUrl.Builder builder = url.newBuilder().addQueryParameter("format", "json");

        PhoneBlockList list = YacbHolder.getPhoneBlockList();
        if (list != null && list.getListVersion() > 0) {
            builder.addQueryParameter("since", String.valueOf(list.getListVersion()));
        }

        /*
         * No range is asked for: a file server understands one, an API that builds its answer
         * may refuse it, and refusing is exactly the false alarm this is meant to end. The
         * answer is dropped unread instead, which closes the connection after the headers.
         */
        Request.Builder requestBuilder = newRequest(builder.build(), source, secret);

        // PhoneBlock knows one way of logging in, whatever the source says about it
        if (source.getAuth() == NumberSource.Auth.NONE && !TextUtils.isEmpty(secret)) {
            requestBuilder.header("Authorization", "Bearer " + secret);
        }

        try (Response response = newClient().newCall(requestBuilder.build()).execute()) {
            Result failure = checkResponse(response);
            if (failure != null) return failure;

            return new Result(Outcome.OK, response.code(), null, null);
        } catch (Exception e) {
            return failed(e);
        }
    }

    /**
     * Asks the address book what it is. A server that doesn't do PROPFIND is asked plainly
     * instead: that no longer proves it speaks CardDAV, but it does say the address and the
     * login are good, which is what the user is here to find out.
     */
    private static Result testCardDav(NumberSource source, String secret) {
        HttpUrl url = HttpUrl.parse(source.getUrl());
        if (url == null) return new Result(Outcome.BAD_URL, 0, null, null);

        RequestBody body = RequestBody.create(
                MediaType.parse("application/xml; charset=utf-8"), PROPFIND_BODY);

        Request request = newRequest(url, source, secret)
                .header("Depth", "0")
                .method("PROPFIND", body)
                .build();

        try (Response response = newClient().newCall(request).execute()) {
            if (response.isSuccessful() || response.code() == MULTI_STATUS) {
                return new Result(Outcome.OK, response.code(), null, null);
            }

            // not everything that holds contacts answers PROPFIND
            if (response.code() == 405 || response.code() == 501) {
                return testPlainGet(url, source, secret);
            }

            Result failure = checkResponse(response);
            return failure != null ? failure : new Result(Outcome.OK, response.code(), null, null);
        } catch (Exception e) {
            return failed(e);
        }
    }

    private static Result testPlainGet(HttpUrl url, NumberSource source, String secret) {
        try (Response response = newClient()
                .newCall(newRequest(url, source, secret).build()).execute()) {
            Result failure = checkResponse(response);
            if (failure != null) return failure;

            return new Result(Outcome.OK, response.code(), null, null);
        } catch (Exception e) {
            return failed(e);
        }
    }

    /** What is wrong with the answer, or null when nothing is. */
    private static Result checkResponse(Response response) {
        if (response.isSuccessful()) return null;

        LOG.warn("the server answered {}", response.code());

        if (response.code() == 401 || response.code() == 403) {
            return new Result(Outcome.UNAUTHORIZED, response.code(), null, null);
        }

        return new Result(Outcome.HTTP_ERROR, response.code(), null, response.message());
    }

    /** Reads the first bytes and names what they are, or null when there are none. */
    private static ArchiveUtils.Format peek(InputStream inputStream) throws IOException {
        BufferedInputStream in = new BufferedInputStream(inputStream, PEEK_SIZE);

        in.mark(PEEK_SIZE);
        if (in.read() < 0) return null;
        in.reset();

        return ArchiveUtils.detect(in);
    }

    private static Result failed(Exception e) {
        LOG.warn("the source couldn't be reached", e);

        String detail = e.getMessage();
        if (TextUtils.isEmpty(detail)) detail = e.getClass().getSimpleName();

        return new Result(Outcome.UNREACHABLE, 0, null, detail);
    }

    private static Request.Builder newRequest(HttpUrl url, NumberSource source, String secret) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", "YetAnotherCallBlocker/" + BuildConfig.VERSION_NAME);

        String authorization = SourceHttp.getAuthorization(source, secret);
        if (authorization != null) builder.header("Authorization", authorization);

        return builder;
    }

    private static OkHttpClient newClient() {
        DeferredInit.initNetwork();

        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

}
