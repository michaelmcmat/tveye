package app.tveye;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Bookmarks: the only thing tveye keeps between runs. Stored in the app's private preferences. */
final class Bookmarks {

    static final class Item {
        final String title, url;
        Item(String title, String url) { this.title = title; this.url = url; }
    }

    private static final String PREFS = "tveye", KEY = "bookmarks";
    private static final int MAX = 24;
    private final SharedPreferences prefs;

    Bookmarks(Context c) { prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    List<Item> all() {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Item(o.optString("t"), o.optString("u")));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    boolean contains(String url) {
        if (url == null) return false;
        for (Item i : all()) if (i.url.equals(url)) return true;
        return false;
    }

    /** Adds the page, or removes it if it is already there. Returns true if it is now a bookmark. */
    boolean toggle(String title, String url) {
        List<Item> items = all();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).url.equals(url)) { items.remove(i); save(items); return false; }
        }
        if (items.size() >= MAX) items.remove(items.size() - 1);
        items.add(0, new Item(title == null || title.isEmpty() ? url : title, url));
        save(items);
        return true;
    }

    private void save(List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item i : items) a.put(new JSONObject().put("t", i.title).put("u", i.url));
        } catch (JSONException ignored) { }
        prefs.edit().putString(KEY, a.toString()).apply();
    }
}
