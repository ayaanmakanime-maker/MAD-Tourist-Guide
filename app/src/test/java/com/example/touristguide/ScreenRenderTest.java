package com.example.touristguide;

import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Renders the main screens to PNG files (app/build/screens) for visual review during development.
 * These are JVM renders (Robolectric native graphics), not device screenshots. Skipped unless run
 * with -PrenderScreens=true.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
public class ScreenRenderTest {

    private File out;

    @Before
    public void setUp() {
        assumeTrue("set -PrenderScreens=true", "true".equals(System.getProperty("renderScreens")));
        out = new File(System.getProperty("user.dir"), "build/screens");
        out.mkdirs();
        Context c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences("tourist_guide", Context.MODE_PRIVATE).edit().clear().commit();
        Store.init(c);
        Store.setBackendUrl("http://127.0.0.1:9");
        LocationRepository.select(LocationRepository.DEFAULT);
        PlaceRepository.clearCache();
        PlanRepository.reload();
        ChatSession.MESSAGES.clear();
        ChatSession.waiting = false;
    }

    private void settle() {
        for (int i = 0; i < 60; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void save(Activity a, String name) throws Exception {
        settle();
        View root = a.getWindow().getDecorView().getRootView();
        Bitmap bmp = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bmp));
        try (FileOutputStream fos = new FileOutputStream(new File(out, name + ".png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }

    private void tab(MainActivity a, int id) {
        ((BottomNavigationView) a.findViewById(R.id.nav)).setSelectedItemId(id);
        settle();
    }

    @Test
    public void renderLight() throws Exception {
        render("light");
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    public void renderDark() throws Exception {
        render("dark");
    }

    private void render(String theme) throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        settle();
        save(a, theme + "-1-explore");

        PlanRepository.add(Catalog.byName("Gateway of India"), 1);
        PlanRepository.add(Catalog.byName("Leopold Cafe"), 1);
        PlanRepository.get().setEstimatedCost(Catalog.byName("Leopold Cafe").id, 600L);
        PlanRepository.get().setNote(Catalog.byName("Gateway of India").id, "Ferry to Elephanta at 9:30");
        PlanRepository.save();
        tab(a, R.id.nav_plan);
        save(a, theme + "-2-plan");

        a.findViewById(R.id.tabBudget).performClick();
        ((EditText) a.findViewById(R.id.total)).setText("5000");
        ((EditText) a.findViewById(R.id.days)).setText("2");
        ((EditText) a.findViewById(R.id.food)).setText("2500");
        ((EditText) a.findViewById(R.id.transport)).setText("1500");
        ((EditText) a.findViewById(R.id.tickets)).setText("1800");
        save(a, theme + "-3-budget");
        a.findViewById(R.id.tabItinerary).performClick();

        tab(a, R.id.nav_chat);
        ((EditText) a.findViewById(R.id.input)).setText("Find restaurants near me under ₹500");
        a.findViewById(R.id.send).performClick();
        long end = System.currentTimeMillis() + 10_000;
        while (ChatSession.waiting && System.currentTimeMillis() < end) settle();
        save(a, theme + "-4-chat");

        tab(a, R.id.nav_more);
        save(a, theme + "-5-more");

        tab(a, R.id.nav_map);
        save(a, theme + "-6-map-nokey");

        tab(a, R.id.nav_explore);
        a.openDetails(Catalog.byName("Gateway of India"));
        settle();
        DetailSheet sheet = (DetailSheet) a.getSupportFragmentManager().findFragmentByTag("detail");
        View sheetRoot = sheet.getDialog().getWindow().getDecorView();
        Bitmap bmp = Bitmap.createBitmap(sheetRoot.getWidth(), sheetRoot.getHeight(), Bitmap.Config.ARGB_8888);
        sheetRoot.draw(new android.graphics.Canvas(bmp));
        try (FileOutputStream fos = new FileOutputStream(new File(out, theme + "-7-detail.png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }

        QuizActivity q = Robolectric.buildActivity(QuizActivity.class).setup().get();
        q.findViewById(R.id.start).performClick();
        ((android.view.ViewGroup) q.findViewById(R.id.options)).getChildAt(1).performClick();
        save(q, theme + "-8-quiz");
    }
}
