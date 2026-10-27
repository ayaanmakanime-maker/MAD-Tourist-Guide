package com.example.touristguide;

import java.util.Arrays;
import java.util.List;

/**
 * The curated offline guide. It is used when live Google Places results are unavailable and is
 * always labelled "Curated guide" in the UI so it is never mistaken for live data.
 * Coordinates are approximate; prices/hours are indicative or deliberately left unknown.
 */
public final class Catalog {

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
                    "🦌", 240, 85, 19.2148, 72.9103, "Nature"),

            // Curated food stops. Menus, prices and opening hours are intentionally not stored.
            new Place("Leopold Cafe", "Café & restaurant", "Colaba",
                    "A Colaba Causeway landmark dating back to 1871 and a long-time favourite with travellers. Check current hours and prices locally.",
                    "☕", 60, Place.UNKNOWN, 18.9227, 72.8317, "Food", "Cafe"),
            new Place("Bademiya", "Street-food kebabs", "Colaba",
                    "A famous late-evening kebab and roll stall in the lanes behind the Taj Mahal Palace hotel. Check current hours locally.",
                    "🍢", 45, Place.UNKNOWN, 18.9232, 72.8322, "Food"),
            new Place("Britannia & Co.", "Parsi restaurant", "Ballard Estate",
                    "A historic Parsi café-restaurant from 1923, known for its berry pulao. Opening days and hours are limited, so confirm before going.",
                    "🍛", 60, Place.UNKNOWN, 18.9346, 72.8404, "Food"),
            new Place("Kyani & Co.", "Irani café", "Marine Lines",
                    "One of Mumbai's oldest Irani cafés (since 1904), known for bun maska and chai. Check current hours locally.",
                    "🫖", 45, Place.UNKNOWN, 18.9440, 72.8290, "Food", "Cafe"),
            new Place("Swati Snacks", "Gujarati vegetarian snacks", "Tardeo",
                    "A popular vegetarian restaurant serving Gujarati and Mumbai snacks. Queues are common at peak times.",
                    "🥟", 60, Place.UNKNOWN, 18.9701, 72.8140, "Food"));

    private Catalog() {
    }

    public static Place byId(String id) {
        for (Place p : PLACES) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /** Lookup used to migrate favourites/plans saved by older versions (which stored names). */
    public static Place byName(String name) {
        for (Place p : PLACES) {
            if (p.name.equals(name)) return p;
        }
        return null;
    }
}
