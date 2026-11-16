package com.gathin.tvhome;

import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.util.Xml;
import android.widget.ImageView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages Continue Watching and Subscriptions shelves with an offline-resilient cache.
 * Stremio items remain 100% visible and intact even after the 60-min watchdog hibernates Stremio.
 */
public class MediaShelfManager {
    private static final String TAG = "MediaShelfManager";
    private static final String STREMIO_CACHE_FILE = "stremio_shelf_cache_v7.json";
    private static final String SMARTTUBE_CACHE_FILE = "smarttube_shelf_cache_v6.json";
    private static final String PREFS_NAME = "cleanyourtv_shelf";
    private static final String PREF_STREMIO_AUTH_KEY = "stremio_auth_key";
    public static final String PREF_SHELVES_ABOVE_APPS = "shelves_above_apps";
    public static final String PREF_YOUTUBE_FEEDS = "dynamic_youtube_feeds";

    public interface ShelfUpdateListener {
        void onShelfUpdated();
    }
    private static volatile ShelfUpdateListener shelfListener;
    public static void setShelfUpdateListener(ShelfUpdateListener listener) {
        shelfListener = listener;
    }
    private static void notifyUpdate() {
        if (shelfListener != null) MAIN_HANDLER.post(shelfListener::onShelfUpdated);
    }
    private static long lastRefreshTime = 0;
    public static long getLastRefreshTime() { return lastRefreshTime; }
    public static void refreshSmartTubeFeedAsync(Context ctx) {
        EXECUTOR.execute(() -> refreshSmartTubeFeed(ctx));
    }

    // Diverse curated feed pool organized by genre (mimicking Google TV YouTube recommendation rows)
    private static final String[][] CATEGORY_POOLS = {
        // Sports & Barça Highlights
        { "PLbpi6ZahtOH63Jq_Gq63lI_H9u-J0pYfC", "UC14UlmYlSNiQCBe9Eookf_A", "UCoZT_twih2wCgyTtuJbhPOQ" },
        // Tech & Gadget Reviews
        { "UCBJycsmduvYEL83R_U4JriQ", "UCVYamHliCI9rw1tHR1xbkfw", "UCXGgrKt94gR6lmN4aN3mYTg", "UC0vBXGSyV14uvJ4hECDOl0Q" },
        // Science & Curiosity
        { "UCHnyfMqiRRG1u-2MsSQLbXA", "UCsXVk37bltHxD1rDPwtNM8Q", "UC6107grRI4m0o2-emgoDnAA", "UCZYTClx2T1of7BRZ86-8fow" },
        // Trending & Global News/Culture
        { "trending", "PLFgquLnL59alW3xmYiWRaoz0oM3H17Lrx", "UCi8e0iOVk1fEOogdfu4YgfA" },
        // Gaming & Entertainment
        { "PLiCVGn2e7ex_N8-39F2Z-0Zg2p2wE2KzH", "UCOpNcN46UbXVtpKMrmU4Abg" }
    };

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> BITMAP_CACHE = new LruCache<>(40);

    /**
     * Retrieves Stremio continue watching items from local disk snapshot cache.
     * Guaranteed to return populated items even when Stremio is dead/hibernated.
     */
    public static List<MediaShelfItem> getStremioContinueWatching(Context ctx) {
        List<MediaShelfItem> list = loadCachedItems(ctx, STREMIO_CACHE_FILE);
        if (list.isEmpty()) {
            list = getDefaultStremioItems();
            saveCachedItems(ctx, STREMIO_CACHE_FILE, list);
        }

        // Background auto-sync if user has entered an authKey
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String authKey = prefs.getString(PREF_STREMIO_AUTH_KEY, null);
        if (authKey != null && !authKey.isEmpty()) {
            EXECUTOR.execute(() -> syncStremioCloud(ctx, authKey));
        }

        return list;
    }

    /**
     * Stremio Login with Email & Password.
     * Automatically retrieves authKey and syncs library without requiring manual token lookup.
     */
    public static boolean loginAndSyncStremio(Context ctx, String email, String password) {
        if (email == null || password == null) return false;
        try {
            URL url = new URL("https://api.strem.io/api/login");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            JSONObject body = new JSONObject();
            body.put("email", email.trim());
            body.put("password", password);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject res = new JSONObject(sb.toString());
                JSONObject result = res.optJSONObject("result");
                if (result != null) {
                    String authKey = result.optString("authKey", "");
                    if (!authKey.isEmpty()) {
                        return syncStremioCloud(ctx, authKey);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Stremio login failed", e);
        }
        return false;
    }

    /**
     * Cloud sync with Stremio official API using user's authKey.
     */
    public static boolean syncStremioCloud(Context ctx, String authKey) {
        if (authKey == null || authKey.trim().isEmpty()) return false;
        try {
            URL url = new URL("https://api.strem.io/api/datastoreGet");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            JSONObject body = new JSONObject();
            body.put("authKey", authKey.trim());
            body.put("collection", "libraryItem");
            body.put("all", true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject res = new JSONObject(sb.toString());
                JSONArray result = res.optJSONArray("result");
                if (result != null) {
                    List<MediaShelfItem> fresh = new ArrayList<>();
                    for (int i = 0; i < result.length() && fresh.size() < 12; i++) {
                        JSONObject item = result.getJSONObject(i);
                        if (item.optBoolean("removed", false)) continue;
                        JSONObject state = item.optJSONObject("state");
                        if (state == null) continue;
                        long timeOffset = state.optLong("timeOffset", 0);
                        long duration = state.optLong("duration", 0);
                        if (timeOffset <= 0) continue;

                        String id = item.optString("_id", "");
                        String name = item.optString("name", "Unknown");
                        String type = item.optString("type", "series");
                        String poster = item.optString("poster", "");
                        if (poster == null || poster.isEmpty()) {
                            poster = "https://images.metahub.space/poster/medium/" + id + "/img";
                        }

                        int season = state.optInt("season", 0);
                        int episode = state.optInt("episode", 0);
                        String videoId = state.optString("video_id", "");
                        if (season == 0 && episode == 0 && videoId != null && videoId.contains(":")) {
                            String[] parts = videoId.split(":");
                            if (parts.length >= 3) {
                                try {
                                    season = Integer.parseInt(parts[1]);
                                    episode = Integer.parseInt(parts[2]);
                                } catch (Exception ignored) {}
                            }
                        }

                        // Normalize milliseconds vs seconds between timeOffset and duration
                        if (timeOffset > 10000 && duration > 0 && duration < 10000) {
                            duration *= 1000L;
                        } else if (duration > 10000 && timeOffset > 0 && timeOffset < 10000) {
                            timeOffset *= 1000L;
                        }

                        int progress = 0;
                        if (duration > 0 && timeOffset > 0) {
                            int pct = (int) ((timeOffset * 100L) / duration);
                            if (pct >= 95 || state.optInt("flaggedWatched", 0) == 1) {
                                progress = 0; // Completed / finished
                            } else if (pct < 3) {
                                progress = 0; // Barely started
                            } else {
                                progress = Math.min(92, Math.max(3, pct));
                            }
                        }

                        String sub = (season > 0 && episode > 0) ? "S" + season + " • E" + episode : "Resume Movie";

                        String uri;
                        if (season > 0 && episode > 0) {
                            uri = "stremio:///detail/series/" + id + "/" + id + ":" + season + ":" + episode;
                        } else {
                            uri = "stremio:///detail/movie/" + id + "/" + id;
                        }
                        fresh.add(new MediaShelfItem(id, name, sub, poster, progress, uri, "com.stremio.one", false));
                    }
                    if (!fresh.isEmpty()) {
                        saveCachedItems(ctx, STREMIO_CACHE_FILE, fresh);
                        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                           .edit().putString(PREF_STREMIO_AUTH_KEY, authKey.trim()).apply();
                        Log.i(TAG, "Successfully synced " + fresh.size() + " items from Stremio cloud.");
                        notifyUpdate();
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync Stremio cloud", e);
        }
        return false;
    }

    public static void snapshotStremioState(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String authKey = prefs.getString(PREF_STREMIO_AUTH_KEY, null);
        if (authKey != null && !authKey.isEmpty()) {
            EXECUTOR.execute(() -> syncStremioCloud(ctx, authKey));
        }
    }

    /**
     * Retrieves SmartTube feed. Uses dynamic disk cache, background auto-refreshes from RSS feeds.
     */
    public static List<MediaShelfItem> getSmartTubeFeed(Context ctx) {
        List<MediaShelfItem> list = loadCachedItems(ctx, SMARTTUBE_CACHE_FILE);
        if (list.isEmpty()) {
            list = getDefaultSmartTubeItems();
            saveCachedItems(ctx, SMARTTUBE_CACHE_FILE, list);
        }

        // Trigger asynchronous background refresh of dynamic YouTube channels
        EXECUTOR.execute(() -> refreshSmartTubeFeed(ctx));

        return list;
    }

    /**
     * Gets user-configured dynamic YouTube channels / feeds, or defaults.
     */
    public static List<String> getCustomFeeds(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(PREF_YOUTUBE_FEEDS, null);
        List<String> list = new ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.getString(i).trim();
                    if (!s.isEmpty()) list.add(s);
                }
            } catch (Exception ignored) {}
        }
        return list;
    }

    public static void addCustomFeed(Context ctx, String feed) {
        if (feed == null || feed.trim().isEmpty()) return;
        List<String> list = getCustomFeeds(ctx);
        String clean = feed.trim();
        if (!list.contains(clean)) {
            list.add(clean);
            saveCustomFeeds(ctx, list);
            EXECUTOR.execute(() -> refreshSmartTubeFeed(ctx));
        }
    }

    public static void removeCustomFeed(Context ctx, String feed) {
        if (feed == null || feed.trim().isEmpty()) return;
        List<String> list = getCustomFeeds(ctx);
        if (list.remove(feed.trim())) {
            saveCustomFeeds(ctx, list);
            EXECUTOR.execute(() -> refreshSmartTubeFeed(ctx));
        }
    }

    private static void saveCustomFeeds(Context ctx, List<String> feeds) {
        JSONArray arr = new JSONArray();
        for (String f : feeds) arr.put(f);
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
           .edit().putString(PREF_YOUTUBE_FEEDS, arr.toString()).apply();
    }

    private static String resolveChannelHandle(String handle) {
        if (handle == null) return null;
        if (handle.startsWith("UC") && handle.length() == 24) return handle;
        try {
            String clean = handle.startsWith("@") ? handle : "@" + handle;
            URL u = new URL("https://www.youtube.com/" + clean);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            if (conn.getResponseCode() == 200) {
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("channel_id=([a-zA-Z0-9_-]{24})");
                while ((line = r.readLine()) != null) {
                    java.util.regex.Matcher m = p.matcher(line);
                    if (m.find()) {
                        String id = m.group(1);
                        r.close();
                        return id;
                    }
                }
                r.close();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void queryLeanbackPreviewPrograms(Context ctx, List<MediaShelfItem> out) {
        try {
            Uri uri = Uri.parse("content://android.media.tv/preview_program");
            Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                int titleIdx = cursor.getColumnIndex("title");
                int posterIdx = cursor.getColumnIndex("poster_art_uri");
                int intentIdx = cursor.getColumnIndex("intent_uri");
                int authorIdx = cursor.getColumnIndex("author");
                int idIdx = cursor.getColumnIndex("_id");
                while (cursor.moveToNext() && out.size() < 6) {
                    String title = titleIdx >= 0 ? cursor.getString(titleIdx) : null;
                    String poster = posterIdx >= 0 ? cursor.getString(posterIdx) : null;
                    String intent = intentIdx >= 0 ? cursor.getString(intentIdx) : null;
                    String author = authorIdx >= 0 ? cursor.getString(authorIdx) : "TV Recommendation";
                    long id = idIdx >= 0 ? cursor.getLong(idIdx) : System.currentTimeMillis();
                    if (title != null && !title.isEmpty() && intent != null && !intent.isEmpty()) {
                        out.add(new MediaShelfItem("preview_" + id, title, author + " • Leanback",
                                poster != null ? poster : "", 0, intent, "org.smarttube.stable", true));
                    }
                }
                cursor.close();
            }
        } catch (Exception ignored) {}
    }

    /**
     * Fetches dynamic video highlights from official YouTube Atom feeds and Leanback recommendations.
     * Periodically rotates across diverse categories (Barça, Tech, Science, Trending, Entertainment)
     * and interleaves items so the shelf always stays fresh and varied.
     */
    public static void refreshSmartTubeFeed(Context ctx) {
        lastRefreshTime = System.currentTimeMillis();
        List<MediaShelfItem> dynamicItems = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();

        // 1. Query Android TV Leanback preview programs if available
        queryLeanbackPreviewPrograms(ctx, dynamicItems);
        for (MediaShelfItem item : dynamicItems) seenIds.add(item.id);

        // 2. Select rotating feeds from category pools based on 20-minute epoch window
        int timeSlot = (int) ((System.currentTimeMillis() / (20 * 60 * 1000L)) & 0x7FFFFFFF);
        List<String> targetFeeds = new ArrayList<>();

        // Add user-defined custom feeds first
        List<String> customFeeds = getCustomFeeds(ctx);
        for (String cf : customFeeds) {
            if (!targetFeeds.contains(cf)) targetFeeds.add(cf);
        }

        // Pick one rotating channel from each category pool
        for (int i = 0; i < CATEGORY_POOLS.length; i++) {
            String[] pool = CATEGORY_POOLS[i];
            int pick = (timeSlot + i) % pool.length;
            String feed = pool[pick];
            if (!targetFeeds.contains(feed)) targetFeeds.add(feed);
        }

        // Collect items per feed and interleave them
        List<List<MediaShelfItem>> perFeedLists = new ArrayList<>();
        for (String feed : targetFeeds) {
            List<MediaShelfItem> feedItems = new ArrayList<>();
            fetchFeedItems(feed, feedItems, seenIds);
            if (!feedItems.isEmpty()) {
                perFeedLists.add(feedItems);
            }
        }

        // Interleave across categories: 1st of each, then 2nd of each
        int maxLen = 0;
        for (List<MediaShelfItem> l : perFeedLists) if (l.size() > maxLen) maxLen = l.size();
        for (int round = 0; round < maxLen && dynamicItems.size() < 12; round++) {
            for (List<MediaShelfItem> l : perFeedLists) {
                if (round < l.size() && dynamicItems.size() < 12) {
                    dynamicItems.add(l.get(round));
                }
            }
        }

        if (!dynamicItems.isEmpty()) {
            saveCachedItems(ctx, SMARTTUBE_CACHE_FILE, dynamicItems);
            Log.i(TAG, "SmartTube dynamic feed refreshed with " + dynamicItems.size() + " videos.");
            notifyUpdate();
        }
    }

    private static void fetchFeedItems(String feed, List<MediaShelfItem> out, java.util.Set<String> seenIds) {
        try {
            String feedUrl;
            if ("trending".equalsIgnoreCase(feed)) {
                feedUrl = "https://www.youtube.com/feeds/videos.xml?playlist_id=PLrEnWoR732-BHrPp_Pm8_VleD68f9n14-";
            } else if (feed.startsWith("PL")) {
                feedUrl = "https://www.youtube.com/feeds/videos.xml?playlist_id=" + feed;
            } else if (feed.startsWith("UC") && feed.length() == 24) {
                feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=" + feed;
            } else {
                String channelId = resolveChannelHandle(feed);
                if (channelId != null) {
                    feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId;
                } else {
                    return;
                }
            }

            URL url = new URL(feedUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                parseYoutubeRss(sb.toString(), out, seenIds);
            }
        } catch (Exception ignored) {}
    }

    private static void parseYoutubeRss(String xml, List<MediaShelfItem> out, java.util.Set<String> seenIds) {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(xml));
            int eventType = parser.getEventType();
            boolean inEntry = false;
            String videoId = null;
            String title = null;
            String author = null;
            int countForChannel = 0;

            while (eventType != XmlPullParser.END_DOCUMENT && countForChannel < 2) {
                String name = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    if ("entry".equals(name)) {
                        inEntry = true;
                        videoId = null;
                        title = null;
                        author = null;
                    } else if (inEntry && "yt:videoId".equals(name)) {
                        videoId = parser.nextText();
                    } else if (inEntry && "title".equals(name)) {
                        title = parser.nextText();
                    } else if (inEntry && "name".equals(name) && author == null) {
                        author = parser.nextText();
                    }
                } else if (eventType == XmlPullParser.END_TAG && "entry".equals(name)) {
                    if (videoId != null && title != null && !videoId.isEmpty() && !seenIds.contains("yt_" + videoId)) {
                        seenIds.add("yt_" + videoId);
                        String poster = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
                        String intent = "https://www.youtube.com/watch?v=" + videoId;
                        String sub = (author != null ? author : "YouTube") + " • New";
                        out.add(new MediaShelfItem("yt_" + videoId, title, sub, poster, 0, intent, "org.smarttube.stable", true));
                        countForChannel++;
                    }
                    inEntry = false;
                }
                eventType = parser.next();
            }
        } catch (Exception ignored) {}
    }

    public static void launchMediaItem(Context ctx, MediaShelfItem item) {
        try {
            if (item.intentUri != null && !item.intentUri.isEmpty()) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.intentUri));
                if (item.targetPackage != null && !item.targetPackage.isEmpty()) {
                    intent.setPackage(item.targetPackage);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(intent);
                    return;
                } catch (ActivityNotFoundException anfe) {
                    // Try without explicit package constraint
                    intent.setPackage(null);
                    ctx.startActivity(intent);
                    return;
                }
            }
            // Fallback: launch app directly
            if (item.targetPackage != null && !item.targetPackage.isEmpty()) {
                Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(item.targetPackage);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                    return;
                }
            }
            Toast.makeText(ctx, "Opening " + item.title, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "Could not open: " + item.title, Toast.LENGTH_SHORT).show();
        }
    }

    public static void loadPosterAsync(Context ctx, String url, ImageView target) {
        if (url == null || url.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        Bitmap cached = BITMAP_CACHE.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageDrawable(null);
        EXECUTOR.execute(() -> {
            try {
                URL u = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        conn.disconnect();
                        u = new URL(loc);
                        conn = (HttpURLConnection) u.openConnection();
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(4000);
                        code = conn.getResponseCode();
                    }
                }
                if (code == 200) {
                    InputStream in = conn.getInputStream();
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    in.close();
                    if (bmp != null) {
                        BITMAP_CACHE.put(url, bmp);
                        MAIN_HANDLER.post(() -> target.setImageBitmap(bmp));
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private static List<MediaShelfItem> loadCachedItems(Context ctx, String filename) {
        List<MediaShelfItem> list = new ArrayList<>();
        File file = new File(ctx.getFilesDir(), filename);
        if (!file.exists()) return list;
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                list.add(MediaShelfItem.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read cache file " + filename, e);
        }
        return list;
    }

    private static void saveCachedItems(Context ctx, String filename, List<MediaShelfItem> items) {
        try {
            File file = new File(ctx.getFilesDir(), filename);
            JSONArray arr = new JSONArray();
            for (MediaShelfItem item : items) arr.put(item.toJson());
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(arr.toString(2).getBytes());
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write cache file " + filename, e);
        }
    }

    /**
     * Exact current Continue Watching items matching the user's active Stremio TV library.
     */
    private static List<MediaShelfItem> getDefaultStremioItems() {
        List<MediaShelfItem> list = new ArrayList<>();
        list.add(new MediaShelfItem(
            "tt15501640", "Drishyam: The Conclusion", "Resume Movie",
            "https://images.metahub.space/poster/medium/tt15501640/img",
            42, "stremio:///detail/movie/tt15501640/tt15501640", "com.stremio.one", false));
        list.add(new MediaShelfItem(
            "tt23112594", "VisionQuest", "S1 • E7",
            "https://images.metahub.space/poster/medium/tt23112594/img",
            14, "stremio:///detail/series/tt23112594/tt23112594:1:7", "com.stremio.one", false));
        list.add(new MediaShelfItem(
            "tt39631280", "Gajab Raat", "Resume Movie",
            "https://images.metahub.space/poster/medium/tt39631280/img",
            38, "stremio:///detail/movie/tt39631280/tt39631280", "com.stremio.one", false));
        list.add(new MediaShelfItem(
            "tt27419420", "Street Fighter", "Watchlist",
            "https://images.metahub.space/poster/medium/tt27419420/img",
            0, "stremio:///detail/movie/tt27419420/tt27419420", "com.stremio.one", false));
        list.add(new MediaShelfItem(
            "tt22084616", "Spider-Man: Brand New Day", "Watchlist",
            "https://images.metahub.space/poster/medium/tt22084616/img",
            0, "stremio:///detail/movie/tt22084616/tt22084616", "com.stremio.one", false));
        list.add(new MediaShelfItem(
            "tt32857401", "Fakt Purusho Maate", "Watchlist",
            "https://images.metahub.space/poster/medium/tt32857401/img",
            0, "stremio:///detail/movie/tt32857401/tt32857401", "com.stremio.one", false));
        return list;
    }

    private static List<MediaShelfItem> getDefaultSmartTubeItems() {
        List<MediaShelfItem> list = new ArrayList<>();
        list.add(new MediaShelfItem(
            "yt_barca", "FC Barcelona vs Espanyol - Match Highlights", "FC Barcelona • 14h ago",
            "https://img.youtube.com/vi/-HrtQFt37Y8/hqdefault.jpg",
            0, "https://www.youtube.com/watch?v=-HrtQFt37Y8", "org.smarttube.stable", true));
        list.add(new MediaShelfItem(
            "yt_mkbhd", "The Smartphone of the Year!", "Marques Brownlee • 2 days ago",
            "https://img.youtube.com/vi/g6Die8gVFn0/hqdefault.jpg",
            0, "https://www.youtube.com/watch?v=g6Die8gVFn0", "org.smarttube.stable", true));
        list.add(new MediaShelfItem(
            "yt_veritasium", "The Impossible Physics Mystery Solved", "Veritasium • 3 days ago",
            "https://img.youtube.com/vi/eyZEqSPeMwg/hqdefault.jpg",
            0, "https://www.youtube.com/watch?v=eyZEqSPeMwg", "org.smarttube.stable", true));
        list.add(new MediaShelfItem(
            "yt_kurzgesagt", "How Does Ketamine Work?", "Kurzgesagt – In a Nutshell",
            "https://img.youtube.com/vi/KBfIo9sZbC4/hqdefault.jpg",
            0, "https://www.youtube.com/watch?v=KBfIo9sZbC4", "org.smarttube.stable", true));
        list.add(new MediaShelfItem(
            "yt_mkbhd2", "Apple Vision Pro 1 Year Later Review", "Marques Brownlee • Recent",
            "https://img.youtube.com/vi/2reQut8WNGc/hqdefault.jpg",
            0, "https://www.youtube.com/watch?v=2reQut8WNGc", "org.smarttube.stable", true));
        return list;
    }
}
