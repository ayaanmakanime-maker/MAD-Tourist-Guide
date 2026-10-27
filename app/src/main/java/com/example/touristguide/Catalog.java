package com.example.touristguide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class Catalog {
    public static final String[] FILTERS = {"All", "Historic", "Beach", "Museum", "Shopping", "Nature"};

    public static final List<Place> PLACES = Arrays.asList(
            new Place("Gateway of India", "Historic landmark", "Colaba",
                    "An iconic waterfront arch overlooking the Arabian Sea. A classic starting point for exploring Colaba and catching ferry trips from the pier.",
                    "🏛️", 60, 0, 18.9220, 72.8347, "Historic"),
            new Place("Marine Drive", "Seaside promenade", "South Mumbai",
                    "A sweeping coastal boulevard known for sea views, evening walks and the famous Queen's Necklace skyline after dark.",
                    "🌊", 60, 0, 18.9430, 72.8238, "Beach", "Nature"),
            new Place("Chhatrapati Shivaji Maharaj Terminus", "Heritage architecture", "Fort",
                    "A UNESCO World Heritage railway terminus celebrated for its Victorian Gothic architecture and lively city atmosphere.",
                    "🚉", 30, 0, 18.9398, 72.8355, "Historic"),
            new Place("Colaba Causeway", "Shopping street", "Colaba",
                    "A lively street for browsing clothing, accessories, souvenirs and cafés a short walk from the waterfront.",
                    "🛍️", 60, 500, 18.9151, 72.8258, "Shopping"),
            new Place("Chhatrapati Shivaji Maharaj Vastu Sangrahalaya", "Museum", "Fort",
                    "A major museum with collections spanning art, archaeology and natural history inside a striking heritage building.",
                    "🖼️", 90, 200, 18.9269, 72.8325, "Museum", "Historic"),
            new Place("Siddhivinayak Temple", "Temple", "Prabhadevi",
                    "A well-known Mumbai temple dedicated to Lord Ganesha. Check current visiting arrangements before travelling.",
                    "🛕", 45, 0, 19.0168, 72.8305, "Historic"),
            new Place("Juhu Beach", "Beach", "Juhu",
                    "A popular beach for sunset views and local street-food stalls. Keep the shoreline clean and follow local safety advice.",
                    "🏖️", 75, 250, 19.0988, 72.8267, "Beach"),
            new Place("Haji Ali Dargah", "Shrine", "Worli",
                    "A white-domed shrine on an islet connected by a causeway, best visited at low tide. Dress modestly and check tide timings.",
                    "🕌", 40, 0, 19.0648, 72.8346, "Historic"),
            new Place("Bandra-Worli Sea Link", "Scenic bridge", "Bandra West",
                    "An eight-lane cable-stayed bridge with sweeping views over the Arabian Sea. Stop at the designated viewpoints nearby.",
                    "🌉", 30, 0, 19.0283, 72.8183, "Nature"),
            new Place("Elephanta Caves", "Island caves", "Elephanta Island",
                    "Rock-cut cave temples across the harbour, reached by ferry from the Gateway of India. A UNESCO World Heritage Site.",
                    "🗿", 180, 150, 18.9636, 72.9284, "Historic", "Nature"),
            new Place("Hanging Gardens", "Terraced gardens", "Malabar Hill",
                    "Terraced gardens on Malabar Hill with flower beds and views over Kamala Nehru Park. Peaceful in the early morning.",
                    "🌿", 45, 0, 18.9455, 72.8085, "Nature"),
            new Place("Girgaon Chowpatty", "Beach", "Girgaon",
                    "A sandy beach at the end of Marine Drive, popular for evening snacks and festival celebrations.",
                    "🌅", 60, 0, 18.9553, 72.8119, "Beach"),
            new Place("Dhobi Ghat", "Open-air laundry", "Mahalaxmi",
                    "The world's largest open-air laundry, an interesting slice of working Mumbai. View from the footbridge across the road.",
                    "👕", 30, 0, 18.9856, 72.8305, "Historic"),
            new Place("Sanjay Gandhi National Park", "National park", "Borivali East",
                    "A large forest park within city limits with walking trails and heritage caves inside. Carry water and follow park rules.",
                    "🦌", 240, 85, 19.2148, 72.9103, "Nature"));

    private Catalog() {
    }

    public static List<Place> filtered(String query, String filter) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Place> out = new ArrayList<>();
        for (Place p : PLACES) {
            boolean text = q.isEmpty()
                    || p.name.toLowerCase(Locale.ROOT).contains(q)
                    || p.category.toLowerCase(Locale.ROOT).contains(q)
                    || p.area.toLowerCase(Locale.ROOT).contains(q);
            boolean tag = filter.equals("All") || p.tags.contains(filter);
            if (text && tag) out.add(p);
        }
        return out;
    }

    public static int indexOf(String name) {
        for (int i = 0; i < PLACES.size(); i++) {
            if (PLACES.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    public static Place byName(String name) {
        int i = indexOf(name);
        return i < 0 ? null : PLACES.get(i);
    }
}
