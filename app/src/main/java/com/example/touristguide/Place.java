package com.example.touristguide;

import java.util.Arrays;
import java.util.List;

public class Place {
    public final String name;
    public final String category;
    public final String area;
    public final String description;
    public final String emoji;
    public final int minutes;
    public final int budget;
    public final double lat;
    public final double lng;
    public final List<String> tags;

    public Place(String name, String category, String area, String description, String emoji,
                 int minutes, int budget, double lat, double lng, String... tags) {
        this.name = name;
        this.category = category;
        this.area = area;
        this.description = description;
        this.emoji = emoji;
        this.minutes = minutes;
        this.budget = budget;
        this.lat = lat;
        this.lng = lng;
        this.tags = Arrays.asList(tags);
    }
}
