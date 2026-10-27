package com.example.touristguide;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.TilesOverlay;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenStreetMap version of the Map tab, used when no Google Maps key is configured.
 * Needs no API key or billing. Tiles come from openstreetmap.org (fine for a student project;
 * see https://operations.osmfoundation.org/policies/tiles/ for heavy use).
 */
final class OsmMap {

    interface Listener {
        void onPlaceTapped(Place place);

        void onUserMovedMap();
    }

    private final MapView map;
    private final Context context;
    private final Listener listener;

    /** Must run before the MapView is inflated: identifies the app and keeps the tile cache private. */
    static void configure(Context context) {
        org.osmdroid.config.IConfigurationProvider config = org.osmdroid.config.Configuration.getInstance();
        config.setUserAgentValue(BuildConfig.APPLICATION_ID + "/" + BuildConfig.VERSION_NAME);
        File base = new File(context.getCacheDir(), "osmdroid");
        config.setOsmdroidBasePath(base);
        config.setOsmdroidTileCache(new File(base, "tiles"));
    }

    @SuppressLint("ClickableViewAccessibility") // touches are passed on to the map unchanged
    OsmMap(MapView map, Listener listener) {
        this.map = map;
        this.context = map.getContext();
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT);
        map.setMinZoomLevel(4.0);
        map.setMaxZoomLevel(19.0);
        if (isNight()) map.getOverlayManager().getTilesOverlay().setColorFilter(TilesOverlay.INVERT_COLORS);
        map.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE) listener.onUserMovedMap();
            return false;
        });
        this.listener = listener;
    }

    private boolean isNight() {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    void onResume() {
        map.onResume();
    }

    void onPause() {
        map.onPause();
    }

    void onDestroy() {
        map.onDetach();
    }

    GeoPoint centre() {
        return new GeoPoint(map.getMapCenter().getLatitude(), map.getMapCenter().getLongitude());
    }

    /** Draws the results, plan stops, the search circle and the selected location. */
    void draw(PlaceRepository.State state, List<Place> places, boolean moveCamera) {
        map.getOverlays().clear();

        GeoPoint centre = new GeoPoint(state.lat, state.lng);
        Polygon circle = new Polygon(map);
        circle.setPoints(Polygon.pointsAsCircle(centre, state.query.radiusMeters));
        circle.getFillPaint().setColor(0x1A0F7A6E);
        circle.getOutlinePaint().setColor(ContextCompat.getColor(context, R.color.accent));
        circle.getOutlinePaint().setStrokeWidth(3f);
        circle.setInfoWindow(null);
        map.getOverlays().add(circle);

        List<GeoPoint> fit = new ArrayList<>();
        for (Place p : places) {
            boolean inPlan = PlanRepository.contains(p.id);
            int colour = inPlan ? R.color.accent : p.food ? R.color.pin_food : R.color.pin_sight;
            Marker m = new Marker(map);
            m.setPosition(new GeoPoint(p.lat, p.lng));
            m.setTitle(p.name);
            m.setIcon(tinted(R.drawable.ic_pin, colour));
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            m.setOnMarkerClickListener((marker, mapView) -> {
                listener.onPlaceTapped(p);
                return true;
            });
            map.getOverlays().add(m);
            if (state.places.contains(p)) fit.add(m.getPosition());
        }

        if (LocationRepository.selected().kind == LocationRepository.Kind.DEVICE) {
            Marker me = new Marker(map);
            me.setPosition(centre);
            me.setIcon(tinted(R.drawable.ic_my_location, R.color.accent));
            me.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            me.setInfoWindow(null);
            map.getOverlays().add(me);
        }

        map.getOverlays().add(new CopyrightOverlay(context));
        map.invalidate();

        if (!moveCamera) return;
        fit.add(centre);
        if (fit.size() < 2) {
            map.getController().setZoom(14.0);
            map.getController().setCenter(centre);
        } else {
            BoundingBox box = BoundingBox.fromGeoPointsSafe(fit).increaseByScale(1.25f);
            // zoomToBoundingBox needs the view to be laid out.
            if (map.getWidth() > 0) map.zoomToBoundingBox(box, false);
            else map.addOnFirstLayoutListener((v, l, t, r, b) -> map.zoomToBoundingBox(box, false));
        }
    }

    void focus(Place p) {
        map.getController().setZoom(16.5);
        map.getController().setCenter(new GeoPoint(p.lat, p.lng));
    }

    /** A 36dp bitmap of a tinted vector icon (osmdroid sizes markers by the bitmap). */
    private Drawable tinted(int drawable, int colour) {
        Drawable d = DrawableCompat.wrap(ContextCompat.getDrawable(context, drawable).mutate());
        DrawableCompat.setTint(d, ContextCompat.getColor(context, colour));
        int size = (int) (36 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(bitmap));
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
