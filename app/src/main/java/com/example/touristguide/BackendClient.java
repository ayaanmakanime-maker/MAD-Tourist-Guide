package com.example.touristguide;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP client for the project's FastAPI backend. All network work runs on a background executor
 * and results are delivered on the main thread. No provider API keys exist on the device.
 */
public final class BackendClient {

    public enum ErrorKind { NETWORK, TIMEOUT, NOT_CONFIGURED, QUOTA, NOT_FOUND, PROVIDER, BAD_REQUEST, SERVER }

    public static final class ApiError {
        public final ErrorKind kind;
        public final int httpCode;
        /** Message from the server, if any (already user-friendly). */
        public final String serverMessage;

        ApiError(ErrorKind kind, int httpCode, String serverMessage) {
            this.kind = kind;
            this.httpCode = httpCode;
            this.serverMessage = serverMessage;
        }

        boolean retryable() {
            // Timeouts and transient gateway errors only; "not configured"/"key denied" won't fix themselves.
            return kind == ErrorKind.TIMEOUT
                    || (kind == ErrorKind.PROVIDER && (httpCode == 502 || httpCode == 504))
                    || (kind == ErrorKind.SERVER && httpCode >= 500);
        }
    }

    public interface Callback<T> {
        void onSuccess(T value);

        void onError(ApiError error);
    }

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int PLACES_READ_TIMEOUT_MS = 20_000;
    private static final int CHAT_READ_TIMEOUT_MS = 45_000;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BackendClient() {
    }

    // ------------------------------------------------------------------ API

    public static void health(Callback<JSONObject> cb) {
        get("/health", new LinkedHashMap<>(), 6_000, 0, cb);
    }

    public static void nearby(double lat, double lng, int radius, String category, String rank,
                              Callback<JSONObject> cb) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("lat", fmt(lat));
        q.put("lng", fmt(lng));
        q.put("radius", String.valueOf(radius));
        q.put("category", category);
        q.put("rank", rank);
        get("/places/nearby", q, PLACES_READ_TIMEOUT_MS, 1, cb);
    }

    public static void search(String text, double lat, double lng, int radius, String category,
                              boolean openNow, String pageToken, Callback<JSONObject> cb) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("q", text);
        q.put("lat", fmt(lat));
        q.put("lng", fmt(lng));
        q.put("radius", String.valueOf(radius));
        q.put("category", category);
        if (openNow) q.put("open_now", "true");
        if (pageToken != null) q.put("page_token", pageToken);
        get("/places/search", q, PLACES_READ_TIMEOUT_MS, 1, cb);
    }

    public static void details(String googlePlaceId, Callback<JSONObject> cb) {
        get("/places/details/" + Uri.encode(googlePlaceId), new LinkedHashMap<>(), PLACES_READ_TIMEOUT_MS, 1, cb);
    }

    public static void photo(String photoName, int maxWidth, Callback<JSONObject> cb) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("name", photoName);
        q.put("max_width", String.valueOf(maxWidth));
        get("/places/photo", q, PLACES_READ_TIMEOUT_MS, 0, cb);
    }

    public static void chat(JSONObject body, Callback<JSONObject> cb) {
        post("/chat", body, CHAT_READ_TIMEOUT_MS, 1, cb);
    }

    /** Runs any blocking work on the shared background executor. */
    static void runInBackground(Runnable r) {
        EXECUTOR.execute(r);
    }

    static void postToMain(Runnable r) {
        MAIN.post(r);
    }

    // ------------------------------------------------------------------ plumbing

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.5f", v);
    }

    private static void get(String path, Map<String, String> query, int readTimeout, int retries,
                            Callback<JSONObject> cb) {
        Uri.Builder b = Uri.parse(Store.backendUrl()).buildUpon().appendEncodedPath(path.substring(1));
        for (Map.Entry<String, String> e : query.entrySet()) b.appendQueryParameter(e.getKey(), e.getValue());
        execute(b.build().toString(), null, readTimeout, retries, cb);
    }

    private static void post(String path, JSONObject body, int readTimeout, int retries, Callback<JSONObject> cb) {
        String url = Uri.parse(Store.backendUrl()).buildUpon().appendEncodedPath(path.substring(1)).build().toString();
        execute(url, body.toString(), readTimeout, retries, cb);
    }

    private static void execute(String url, String body, int readTimeout, int retries, Callback<JSONObject> cb) {
        EXECUTOR.execute(() -> {
            Object result = null;
            for (int attempt = 0; attempt <= retries; attempt++) {
                result = request(url, body, readTimeout);
                if (!(result instanceof ApiError) || !((ApiError) result).retryable() || attempt == retries) break;
                try {
                    Thread.sleep(1200L * (attempt + 1)); // simple backoff before the single retry
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            final Object finalResult = result;
            MAIN.post(() -> {
                if (finalResult instanceof JSONObject) cb.onSuccess((JSONObject) finalResult);
                else cb.onError((ApiError) finalResult);
            });
        });
    }

    /** Performs one HTTP request. Returns a JSONObject on success or an ApiError. */
    private static Object request(String url, String body, int readTimeout) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeout);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "TouristGuide-Android/" + BuildConfig.VERSION_NAME);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return new JSONObject(read(conn.getInputStream()));
            }
            return errorFrom(code, read(conn.getErrorStream()));
        } catch (SocketTimeoutException e) {
            return new ApiError(ErrorKind.TIMEOUT, 0, null);
        } catch (UnknownHostException | ConnectException | NoRouteToHostException e) {
            return new ApiError(ErrorKind.NETWORK, 0, null);
        } catch (IOException e) {
            return new ApiError(ErrorKind.NETWORK, 0, null);
        } catch (Exception e) {
            return new ApiError(ErrorKind.SERVER, 0, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static ApiError errorFrom(int code, String body) {
        String kind = "";
        String message = null;
        try {
            JSONObject detail = new JSONObject(body).optJSONObject("detail");
            if (detail != null) {
                kind = detail.optString("kind", "");
                message = detail.optString("message", null);
            }
        } catch (Exception ignored) {
            // Non-JSON error body (e.g. a proxy page): fall back to the status code.
        }
        ErrorKind k;
        if (kind.endsWith("not_configured")) k = ErrorKind.NOT_CONFIGURED;
        else if (code == 429 || kind.equals("quota") || kind.equals("rate_limited") || kind.equals("daily_limit")) k = ErrorKind.QUOTA;
        else if (code == 404) k = ErrorKind.NOT_FOUND;
        else if (code == 504 || kind.endsWith("timeout")) k = ErrorKind.TIMEOUT;
        else if (code == 422 || code == 400) k = ErrorKind.BAD_REQUEST;
        else if (kind.startsWith("provider") || kind.startsWith("ai_")) k = ErrorKind.PROVIDER;
        else k = ErrorKind.SERVER;
        return new ApiError(k, code, message);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) > 0) {
                out.write(buffer, 0, count);
                if (out.size() > 2_000_000) throw new IOException("Response too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
