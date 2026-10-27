package com.example.touristguide;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Calls the Gemini API directly from the app. No backend server is required.
 * The API key is injected at build time from local.properties.
 */
public final class AiClient {
    public interface Listener {
        void onReply(String reply, String mode);

        void onError(String message);
    }

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String DEFAULT_MODEL = "gemini-3.1-flash-lite";
    private static final String FALLBACK_MODEL = "gemini-3-flash-preview";
    private static final String SYSTEM_PROMPT =
            "You are a careful travel assistant inside a tourist guide app. "
                    + "The selected city is Mumbai. Give practical, concise itineraries, "
                    + "ask clarifying questions if needed, and distinguish estimates from "
                    + "verified facts. Do not invent live opening hours, prices, ratings, or "
                    + "availability. Encourage users to verify current details from official "
                    + "sources. Keep safety and accessibility in mind. Answer in the same "
                    + "language the user writes in. Keep answers under 150 words.";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AiClient() {
    }

    public static void chat(String text, Listener listener) {
        String key = BuildConfig.GEMINI_API_KEY == null
                ? ""
                : BuildConfig.GEMINI_API_KEY.trim();
        if (key.isEmpty()) {
            MAIN.post(() -> listener.onError(
                    "Gemini API key is not configured. Add GEMINI_API_KEY to local.properties and rebuild."));
            return;
        }

        String payload = toJson(text);
        EXECUTOR.execute(() -> {
            String lastError = null;
            for (String model : models()) {
                Result result = attempt(model, key, payload);
                if (result.reply != null) {
                    String reply = result.reply;
                    MAIN.post(() -> listener.onReply(reply, "gemini"));
                    return;
                }
                lastError = result.error;
                if (!result.retryable) break;
            }
            String error = lastError == null ? "Gemini request failed." : lastError;
            MAIN.post(() -> listener.onError(error));
        });
    }

    /**
     * The configured model first, then a currently-working fallback. Retired
     * models, rate limits and provider 5xx responses automatically move on to
     * the next entry.
     */
    private static String[] models() {
        String primary = BuildConfig.GEMINI_MODEL == null || BuildConfig.GEMINI_MODEL.trim().isEmpty()
                ? DEFAULT_MODEL
                : BuildConfig.GEMINI_MODEL.trim();
        Set<String> models = new LinkedHashSet<>();
        models.add(primary);
        models.add(FALLBACK_MODEL);
        models.add(DEFAULT_MODEL);
        return models.toArray(new String[0]);
    }

    private static Result attempt(String model, String key, String payload) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT + model + ":generateContent");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(25000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("x-goog-api-key", key);

            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                JSONObject body = new JSONObject(read(conn.getInputStream()));
                Reply reply = parseReply(body);
                if (reply.error != null) {
                    return new Result(null, reply.error, false);
                }
                return new Result(reply.text, null, false);
            }

            String detail = errorDetail(conn, code);
            boolean retryable = code == 404 || code == 429 || code >= 500;
            return new Result(null, detail, retryable);
        } catch (UnknownHostException e) {
            return new Result(null,
                    "Can't reach Gemini. Check your internet connection and try again.", false);
        } catch (SocketTimeoutException e) {
            return new Result(null,
                    "Gemini took too long to respond. Please try again.", true);
        } catch (Exception e) {
            String reason = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? "Please try again."
                    : e.getMessage();
            return new Result(null, "Gemini request failed. " + reason, false);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Reply parseReply(JSONObject body) {
        if (body.has("error")) {
            JSONObject error = body.optJSONObject("error");
            String message = error == null
                    ? "Gemini request failed."
                    : error.optString("message", "Gemini request failed.");
            return new Reply(null, message);
        }

        JSONArray candidates = body.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            JSONObject feedback = body.optJSONObject("promptFeedback");
            String block = feedback == null ? "" : feedback.optString("blockReason", "");
            return new Reply(null, block.isEmpty()
                    ? "Gemini did not return an answer. Try rephrasing your question."
                    : "Gemini blocked this request (" + block + "). Try rephrasing it.");
        }

        JSONObject candidate = candidates.optJSONObject(0);
        if (candidate == null) {
            return new Reply(null, "Gemini did not return an answer. Try again.");
        }

        JSONObject content = candidate.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null || part.optBoolean("thought", false)) continue;
                String piece = part.optString("text", "");
                if (piece.isEmpty()) continue;
                if (text.length() > 0) text.append('\n');
                text.append(piece);
            }
        }

        String result = text.toString().trim();
        if (result.isEmpty()) {
            String finish = candidate.optString("finishReason", "");
            if ("SAFETY".equals(finish) || "RECITATION".equals(finish)
                    || "BLOCKLIST".equals(finish) || "PROHIBITED_CONTENT".equals(finish)) {
                return new Reply(null, "Gemini blocked this response. Try rephrasing your question.");
            }
            return new Reply(null, "Gemini returned an empty answer. Try again.");
        }
        return new Reply(result, null);
    }

    private static String errorDetail(HttpURLConnection conn, int code) {
        String provider = "";
        try {
            InputStream err = conn.getErrorStream();
            if (err != null) {
                JSONObject body = new JSONObject(read(err));
                JSONObject error = body.optJSONObject("error");
                if (error != null) provider = error.optString("message", "");
            }
        } catch (Exception ignored) {
            // Fall back to the HTTP status message below.
        }

        String prefix;
        if (code == 400) {
            prefix = "Gemini rejected the request.";
        } else if (code == 401 || code == 403) {
            prefix = "Gemini API key is invalid or has no access.";
        } else if (code == 429) {
            prefix = "Gemini rate limit reached. Try again in a minute.";
        } else if (code >= 500) {
            prefix = "Gemini is busy right now. Try again.";
        } else {
            prefix = "Gemini returned HTTP " + code + ".";
        }

        if (provider.isEmpty()) return prefix;
        return prefix + " " + shorten(provider, 160);
    }

    private static String shorten(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    private static String toJson(String text) {
        try {
            JSONObject system = new JSONObject();
            system.put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM_PROMPT)));

            JSONObject content = new JSONObject();
            content.put("role", "user");
            content.put("parts", new JSONArray().put(new JSONObject().put("text", text)));

            JSONObject generation = new JSONObject();
            generation.put("temperature", 0.5);
            generation.put("maxOutputTokens", 700);

            JSONObject payload = new JSONObject();
            payload.put("system_instruction", system);
            payload.put("contents", new JSONArray().put(content));
            payload.put("generationConfig", generation);
            return payload.toString();
        } catch (Exception e) {
            return "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"\"}]}]}";
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream stream = in;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class Reply {
        final String text;
        final String error;

        Reply(String text, String error) {
            this.text = text;
            this.error = error;
        }
    }

    private static final class Result {
        final String reply;
        final String error;
        final boolean retryable;

        Result(String reply, String error, boolean retryable) {
            this.reply = reply;
            this.error = error;
            this.retryable = retryable;
        }
    }
}
