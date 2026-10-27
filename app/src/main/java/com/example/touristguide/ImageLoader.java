package com.example.touristguide;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Minimal photo loader for place photos: asks the backend for a short-lived Google photo URL,
 * downloads it off the main thread, downsamples it and keeps a small in-memory cache.
 */
final class ImageLoader {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    interface Callback {
        void onLoaded(Bitmap bitmap);

        void onFailed();
    }

    private ImageLoader() {
    }

    static void loadPlacePhoto(Place place, int widthPx, ImageView target, Callback cb) {
        String key = place.photoName;
        if (key == null) {
            cb.onFailed();
            return;
        }
        target.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            cb.onLoaded(cached);
            return;
        }
        BackendClient.photo(key, Math.min(1600, Math.max(200, widthPx)), new BackendClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                String uri = value.optString("uri", "");
                if (!uri.startsWith("https://")) {
                    cb.onFailed();
                    return;
                }
                BackendClient.runInBackground(() -> {
                    Bitmap bitmap = download(uri, widthPx);
                    BackendClient.postToMain(() -> {
                        if (bitmap == null) {
                            cb.onFailed();
                            return;
                        }
                        CACHE.put(key, bitmap);
                        if (key.equals(target.getTag())) {
                            target.setImageBitmap(bitmap);
                            cb.onLoaded(bitmap);
                        }
                    });
                });
            }

            @Override
            public void onError(BackendClient.ApiError error) {
                cb.onFailed();
            }
        });
    }

    private static Bitmap download(String uri, int targetWidth) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            if (conn.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_BYTES) return null;
                }
                data = out.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= targetWidth) opts.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
