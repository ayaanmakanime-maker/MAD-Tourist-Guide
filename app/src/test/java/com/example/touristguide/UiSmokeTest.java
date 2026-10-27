package com.example.touristguide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.util.function.BooleanSupplier;

/**
 * Smoke tests that run the real screens on the JVM (Robolectric). The backend URL points at a
 * closed local port, so every network call fails fast and the offline/fallback paths are exercised.
 * These complement, not replace, testing on a real device.
 */
@RunWith(RobolectricTestRunner.class)
public class UiSmokeTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("tourist_guide", Context.MODE_PRIVATE).edit().clear().commit();
        Store.init(context);
        Store.setBackendUrl("http://127.0.0.1:9"); // nothing listens here: connection refused
        LocationRepository.select(LocationRepository.DEFAULT);
        PlaceRepository.clearCache();
        PlanRepository.reload();
        ChatSession.MESSAGES.clear();
        ChatSession.waiting = false;
        ChatSession.lastQuestion = "";
    }

    private static void waitFor(String what, BooleanSupplier condition) {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static MainActivity launchMain() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        shadowOf(Looper.getMainLooper()).idle();
        return c.get();
    }

    private static Fragment currentTab(MainActivity a) {
        return a.getSupportFragmentManager().findFragmentById(R.id.container);
    }

    private static void selectTab(MainActivity a, int id) {
        ((BottomNavigationView) a.findViewById(R.id.nav)).setSelectedItemId(id);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static boolean containsText(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(text)) return true;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) if (containsText(g.getChildAt(i), text)) return true;
        }
        return false;
    }

    @Test
    public void exploreFallsBackToClearlyLabelledCuratedGuide() {
        MainActivity a = launchMain();
        assertTrue(currentTab(a) instanceof ExploreFragment);
        waitFor("fallback", () -> PlaceRepository.state() != null
                && PlaceRepository.state().status == PlaceRepository.Status.CURATED_FALLBACK);
        PlaceRepository.State s = PlaceRepository.state();
        assertEquals(BackendClient.ErrorKind.NETWORK, s.error.kind);
        assertFalse(s.places.isEmpty());
        for (Place p : s.places) assertFalse("fallback must not contain live places", p.isLive());

        RecyclerView list = a.findViewById(R.id.list);
        list.measure(0, 0);
        list.layout(0, 0, 1080, 6000);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(containsText(list, a.getString(R.string.banner_curated_title)));
        assertTrue(containsText(list, a.getString(R.string.source_curated)));
    }

    @Test
    public void everyTabOpensWithoutCrashing() {
        MainActivity a = launchMain();
        int[] tabs = {R.id.nav_map, R.id.nav_plan, R.id.nav_chat, R.id.nav_more, R.id.nav_explore};
        Class<?>[] expected = {MapTabFragment.class, PlanFragment.class, ChatFragment.class,
                MoreFragment.class, ExploreFragment.class};
        for (int i = 0; i < tabs.length; i++) {
            selectTab(a, tabs[i]);
            assertTrue(expected[i].getSimpleName(), expected[i].isInstance(currentTab(a)));
        }
    }

    @Test
    public void mapWithoutGoogleKeyUsesOpenStreetMap() {
        MainActivity a = launchMain();
        // Only meaningful for builds without MAPS_API_KEY (with a key, Google Maps is used instead).
        org.junit.Assume.assumeFalse(MapTabFragment.hasGoogleMapsKey(a));
        selectTab(a, R.id.nav_map);
        assertEquals(View.VISIBLE, a.findViewById(R.id.osmMap).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.map).getVisibility());
        waitFor("markers", () -> ((org.osmdroid.views.MapView) a.findViewById(R.id.osmMap)).getOverlays().size()
                > Catalog.PLACES.size());
    }

    @Test
    public void placeAddedFromExploreAppearsInPlanAndSurvivesRestart() {
        MainActivity a = launchMain();
        Place gateway = Catalog.byName("Gateway of India");
        a.addToPlan(gateway, null);
        a.addToPlan(gateway, null); // duplicate is refused
        assertEquals(1, PlanRepository.get().size());

        selectTab(a, R.id.nav_plan);
        RecyclerView list = a.findViewById(R.id.list);
        list.measure(0, 0);
        list.layout(0, 0, 1080, 4000);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(containsText(list, "Gateway of India"));

        // Simulate a restart: drop the in-memory copy and reload from storage.
        PlanRepository.reload();
        assertTrue(PlanRepository.contains(gateway.id));
    }

    @Test
    public void budgetUpdatesAsYouTypeWarnsWhenOverAndPersists() {
        MainActivity a = launchMain();
        selectTab(a, R.id.nav_plan);
        a.findViewById(R.id.tabBudget).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        ((EditText) a.findViewById(R.id.total)).setText("5000");
        ((EditText) a.findViewById(R.id.days)).setText("2");
        ((EditText) a.findViewById(R.id.food)).setText("2,000");
        ((EditText) a.findViewById(R.id.transport)).setText("1000");
        TextView headline = a.findViewById(R.id.resultHeadline);
        assertEquals(a.getString(R.string.budget_left_headline_fmt, "₹2,000"), headline.getText().toString());
        assertEquals(View.GONE, a.findViewById(R.id.overWarning).getVisibility());

        ((EditText) a.findViewById(R.id.shopping)).setText("4000");
        assertEquals(a.getString(R.string.budget_over_headline_fmt, "₹2,000"), headline.getText().toString());
        assertEquals(View.VISIBLE, a.findViewById(R.id.overWarning).getVisibility());

        ((EditText) a.findViewById(R.id.other)).setText("-5");
        assertNotNull(((com.google.android.material.textfield.TextInputLayout) a.findViewById(R.id.otherLayout)).getError());

        assertEquals(5000, BudgetRepository.inputs().total);
        assertEquals(4000, BudgetRepository.inputs().shopping);
    }

    @Test
    public void chatFallsBackToGroundedOfflineSuggestion() {
        MainActivity a = launchMain();
        selectTab(a, R.id.nav_chat);
        ((EditText) a.findViewById(R.id.input)).setText("Find restaurants near me under ₹500");
        a.findViewById(R.id.send).performClick();
        waitFor("offline answer", () -> !ChatSession.waiting);
        ChatAdapter.Msg last = ChatSession.MESSAGES.get(ChatSession.MESSAGES.size() - 1);
        assertEquals(ChatAdapter.BOT_OFFLINE, last.kind);
        assertFalse(last.places.isEmpty());
        for (Place p : last.places) assertTrue(p.name + " should be a food place", p.food);
    }

    @Test
    public void quizCanBePlayedToTheEndAndRecordsBestScore() {
        QuizActivity q = Robolectric.buildActivity(QuizActivity.class).setup().get();
        q.findViewById(R.id.start).performClick();
        for (int i = 0; i < QuizBank.SESSION_LENGTH; i++) {
            ViewGroup options = q.findViewById(R.id.options);
            assertEquals(4, options.getChildCount());
            options.getChildAt(0).performClick();
            assertEquals(View.VISIBLE, q.findViewById(R.id.feedback).getVisibility());
            q.findViewById(R.id.next).performClick();
        }
        assertEquals(View.VISIBLE, q.findViewById(R.id.result).getVisibility());
        assertNotNull(Store.quizBest("ALL|EASY"));
        assertEquals(1, Store.quizzesPlayed());
    }

    @Test
    public void accountScreenIsHonestWhenFirebaseIsNotConfigured() {
        AccountActivity acc = Robolectric.buildActivity(AccountActivity.class).setup().get();
        assertEquals(View.GONE, acc.findViewById(R.id.form).getVisibility());
        assertEquals(acc.getString(R.string.account_not_configured),
                ((TextView) acc.findViewById(R.id.accountIntro)).getText().toString());
    }

    @Test
    public void detailSheetOpensForCuratedPlaceAndClosesForUnknownId() {
        MainActivity a = launchMain();
        a.openDetails(Catalog.byName("Marine Drive"));
        shadowOf(Looper.getMainLooper()).idle();
        DetailSheet sheet = (DetailSheet) a.getSupportFragmentManager().findFragmentByTag("detail");
        assertNotNull(sheet);
        View root = sheet.requireView();
        assertTrue(containsText(root, "Marine Drive"));
        assertTrue(containsText(root, a.getString(R.string.source_curated)));
        sheet.dismissNow();

        DetailSheet.newInstance("google:does-not-exist").show(a.getSupportFragmentManager(), "missing");
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(a.getSupportFragmentManager().findFragmentByTag("missing") == null
                || !a.getSupportFragmentManager().findFragmentByTag("missing").isAdded());
    }

    @Test
    public void choosingAnAreaWithoutPermissionReloadsAroundIt() {
        MainActivity a = launchMain();
        LocationRepository.Selected bandra = LocationRepository.PRESETS.get(4);
        LocationRepository.select(bandra);
        PlaceRepository.load(PlaceQuery.defaults(), true);
        waitFor("reload", () -> PlaceRepository.state().status == PlaceRepository.Status.CURATED_FALLBACK);
        assertEquals(bandra.lat, PlaceRepository.state().lat, 1e-9);
        assertFalse(LocationRepository.hasPermission(a));
    }
}
