package com.example.touristguide;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Store {
    private static final String PREFS = "tourist_guide";
    private static final String SEP = "";
    private static SharedPreferences p;

    private Store() {
    }

    public static void init(Context context) {
        p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isSaved(String name) {
        return p.getStringSet("favorites", new HashSet<>()).contains(name);
    }

    public static boolean toggleSaved(String name) {
        Set<String> set = new HashSet<>(p.getStringSet("favorites", new HashSet<>()));
        boolean added;
        if (set.contains(name)) {
            set.remove(name);
            added = false;
        } else {
            set.add(name);
            added = true;
        }
        p.edit().putStringSet("favorites", set).apply();
        return added;
    }

    public static int savedCount() {
        return p.getStringSet("favorites", new HashSet<>()).size();
    }

    public static List<Place> savedPlaces() {
        return byOrder(p.getStringSet("favorites", new HashSet<>()));
    }

    public static boolean inPlan(String name) {
        return planList().contains(name);
    }

    public static void addToPlan(String name) {
        List<String> list = planList();
        if (list.contains(name)) return;
        list.add(name);
        savePlan(list);
    }

    public static void removeFromPlan(String name) {
        List<String> list = planList();
        list.remove(name);
        savePlan(list);
    }

    public static void savePlan(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(list.get(i));
        }
        p.edit().putString("plan", sb.toString()).apply();
    }

    public static List<String> planList() {
        String raw = p.getString("plan", "");
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split(SEP)));
    }

    public static List<Place> planPlaces() {
        List<Place> out = new ArrayList<>();
        for (String name : planList()) {
            Place pl = Catalog.byName(name);
            if (pl != null) out.add(pl);
        }
        return out;
    }

    public static void reorderPlanByArea() {
        List<Place> stops = planPlaces();
        if (stops.size() < 2) return;
        stops.sort((a, b) -> a.area.compareTo(b.area) != 0
                ? a.area.compareTo(b.area)
                : a.name.compareTo(b.name));
        List<String> names = new ArrayList<>();
        for (Place pl : stops) names.add(pl.name);
        savePlan(names);
    }

    public static void clearPlan() {
        p.edit().remove("plan").apply();
    }

    public static int planCount() {
        return planList().size();
    }

    public static void replacePlan(List<String> names) {
        List<String> valid = new ArrayList<>();
        for (String name : names) {
            if (Catalog.byName(name) != null && !valid.contains(name)) valid.add(name);
        }
        if (!valid.isEmpty()) savePlan(valid);
    }

    public static int quizIndex() {
        return p.getInt("quiz", 0);
    }

    public static void setQuizIndex(int index) {
        p.edit().putInt("quiz", index % 3).apply();
    }

    public static boolean quizAnswerVisible() {
        return p.getBoolean("quiz_answer", false);
    }

    public static void setQuizAnswerVisible(boolean visible) {
        p.edit().putBoolean("quiz_answer", visible).apply();
    }

    private static List<Place> byOrder(Set<String> names) {
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < Catalog.PLACES.size(); i++) {
            Place pl = Catalog.PLACES.get(i);
            if (names.contains(pl.name)) out.add(pl);
        }
        return out;
    }
}
