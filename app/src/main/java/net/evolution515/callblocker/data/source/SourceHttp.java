package net.evolution515.callblocker.data.source;

import android.text.TextUtils;
import android.util.Base64;

import androidx.core.util.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import dummydomain.yetanothercallblocker.sia.network.OkHttpClientFactory;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Okio;

/**
 * Fetching from a source: saying who we are, and reading what comes back.
 *
 * <p>Two things happen on the way. The request carries whatever the source needs to let us
 * in - a bearer token, or a user and a password. And the answer is unpacked if it arrives
 * packed: the same database is served as a plain file here, gzipped there, and inside a
 * tar.gz or a zip somewhere else, so what it is gets decided by looking at it.
 *
 * <p>Compressing the transfer itself is left to OkHttp, which asks for gzip on every request
 * and unpacks the answer before anyone sees it. That covers the case of a plain file on a
 * server that can compress it in flight; a file that arrives already packed is another
 * matter, and that is what this deals with.
 */
public class SourceHttp {

    private static final Logger LOG = LoggerFactory.getLogger(SourceHttp.class);

    private SourceHttp() {
    }

    /**
     * A client factory for one source, for the parts of the app that take one.
     *
     * @param base where the plain client comes from; it is asked again for every request,
     *             the way the app builds its clients elsewhere
     * @param secret the token or password, or null when the source needs none
     */
    public static OkHttpClientFactory getClientFactory(NumberSource source, String secret,
                                                      Supplier<OkHttpClient> base) {
        return () -> decorate(base.get(), source, secret, true);
    }

    /** The same client, with what this source needs on the way in and out. */
    public static OkHttpClient decorate(OkHttpClient client, NumberSource source, String secret) {
        return decorate(client, source, secret, true);
    }

    /**
     * The same client, with what this source needs on the way in and out.
     *
     * @param unpack whether the answer is unpacked before whoever asked for it sees it. Only
     *               for a caller that reads the database out of the body itself: the code
     *               that unpacks the community database reads the archive it was sent, and
     *               handing it the first file out of that archive leaves it with nothing to
     *               unpack - an empty database that then replaces the one that worked.
     */
    public static OkHttpClient decorate(OkHttpClient client, NumberSource source, String secret,
                                        boolean unpack) {
        OkHttpClient.Builder builder = client != null
                ? client.newBuilder() : new OkHttpClient.Builder();

        builder.addInterceptor(new AuthInterceptor(source, secret));

        if (unpack) builder.addInterceptor(new UnpackingInterceptor());

        return builder.build();
    }

    /** What goes in the Authorization header, or null when the source wants none. */
    public static String getAuthorization(NumberSource source, String secret) {
        if (source == null) return null;

        switch (source.getAuth()) {
            case BEARER:
                return !TextUtils.isEmpty(secret) ? "Bearer " + secret : null;

            case BASIC:
                if (TextUtils.isEmpty(source.getUsername())) return null;

                String credentials = source.getUsername() + ":"
                        + (secret != null ? secret : "");

                return "Basic " + Base64.encodeToString(
                        credentials.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                        Base64.NO_WRAP);

            default:
                return null;
        }
    }

    /** Says who we are, when the source asks. */
    private static class AuthInterceptor implements Interceptor {

        private final NumberSource source;
        private final String secret;

        AuthInterceptor(NumberSource source, String secret) {
            this.source = source;
            this.secret = secret;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            String authorization = getAuthorization(source, secret);

            if (authorization != null && request.header("Authorization") == null) {
                request = request.newBuilder()
                        .header("Authorization", authorization)
                        .build();
            }

            return chain.proceed(request);
        }
    }

    /**
     * Hands on what was inside the archive, so that everything downstream reads the database
     * and knows nothing about how it travelled.
     */
    private static class UnpackingInterceptor implements Interceptor {

        @Override
        public Response intercept(Chain chain) throws IOException {
            Response response = chain.proceed(chain.request());

            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return response;

            // the length is not known before it is unpacked, and -1 says as much
            ResponseBody unpacked = ResponseBody.create(body.contentType(), -1,
                    Okio.buffer(Okio.source(ArchiveUtils.open(body.byteStream()))));

            return response.newBuilder().body(unpacked).build();
        }
    }

}
