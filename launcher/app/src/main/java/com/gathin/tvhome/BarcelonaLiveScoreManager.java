package com.gathin.tvhome;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time live score tracker for FC Barcelona matches.
 * Connects to ESPN public scoreboard endpoints to track live scores and match clock.
 * Always formats Barça's score on the left, immediately adjacent to the club crest.
 */
public final class BarcelonaLiveScoreManager {
    private static final String TAG = "BarcaLiveScore";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);

    private static volatile LiveScore currentScore = null;
    private static volatile long lastFetchTime = 0;

    public static final class LiveScore {
        public final boolean isLive;
        public final boolean isCompleted;
        public final String barcaScore;
        public final String oppScore;
        public final String oppAbbr;
        public final String clock; // e.g. "74'", "HT", "FT"
        public final String competition;

        public LiveScore(boolean isLive, boolean isCompleted, String barcaScore, String oppScore,
                         String oppAbbr, String clock, String competition) {
            this.isLive = isLive;
            this.isCompleted = isCompleted;
            this.barcaScore = barcaScore;
            this.oppScore = oppScore;
            this.oppAbbr = oppAbbr;
            this.clock = clock;
            this.competition = competition;
        }

        /** Formats label with Barça score first, directly next to the crest. */
        public String formatLabel() {
            if (isLive) {
                return String.format(Locale.US, "%s - %s • %s • %s", barcaScore, oppScore, oppAbbr, clock);
            } else if (isCompleted) {
                return String.format(Locale.US, "%s - %s • %s • FT", barcaScore, oppScore, oppAbbr);
            } else {
                return String.format(Locale.US, "0 - 0 • %s • %s", oppAbbr, clock);
            }
        }
    }

    public interface ScoreCallback {
        void onScoreUpdated(LiveScore score);
    }

    public static LiveScore getCurrentScore() {
        return currentScore;
    }

    /**
     * Polls live score if a match is currently scheduled or in progress.
     */
    public static void checkLiveScoreAsync(BarcelonaFixture fixture, ScoreCallback callback) {
        long now = System.currentTimeMillis();
        // Cache for 2.5 seconds to avoid flooding network
        if (now - lastFetchTime < 2500 && currentScore != null) {
            if (callback != null) callback.onScoreUpdated(currentScore);
            return;
        }

        if (FETCHING.compareAndSet(false, true)) {
            EXECUTOR.execute(() -> {
                try {
                    LiveScore score = fetchScoreFromEspn(fixture);
                    if (score != null) {
                        currentScore = score;
                        lastFetchTime = System.currentTimeMillis();
                        if (callback != null) callback.onScoreUpdated(score);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to fetch live score: " + e.getMessage());
                } finally {
                    FETCHING.set(false);
                }
            });
        }
    }

    private static LiveScore fetchScoreFromEspn(BarcelonaFixture fixture) {
        String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // Endpoints to check: Champions League, La Liga, and team schedule
        String[] endpoints = new String[]{
            "https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/scoreboard?dates=" + today,
            "https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/scoreboard?dates=" + yesterday,
            "https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard?dates=" + today,
            "https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard?dates=" + yesterday,
            "https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/teams/83/schedule"
        };

        for (String urlStr : endpoints) {
            try {
                LiveScore score = parseEspnUrl(urlStr);
                if (score != null) return score;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static LiveScore parseEspnUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            JSONArray events = json.optJSONArray("events");
            if (events == null || events.length() == 0) return null;

            for (int i = 0; i < events.length(); i++) {
                JSONObject ev = events.getJSONObject(i);
                String name = ev.optString("name", "");
                if (!name.toLowerCase(Locale.US).contains("barcelona")) continue;

                JSONArray comps = ev.optJSONArray("competitions");
                if (comps == null || comps.length() == 0) continue;
                JSONObject comp = comps.getJSONObject(0);

                JSONObject status = comp.optJSONObject("status");
                if (status == null) continue;
                JSONObject statusType = status.optJSONObject("type");
                String state = statusType != null ? statusType.optString("state", "") : "";
                boolean completed = statusType != null && statusType.optBoolean("completed", false);
                String clock = status.optString("displayClock", "");
                if (clock.isEmpty() && statusType != null) {
                    clock = statusType.optString("shortDetail", "");
                }
                if (clock.isEmpty()) clock = "LIVE";

                JSONArray competitors = comp.optJSONArray("competitors");
                if (competitors == null || competitors.length() < 2) continue;

                JSONObject barcaComp = null;
                JSONObject oppComp = null;

                for (int j = 0; j < competitors.length(); j++) {
                    JSONObject c = competitors.getJSONObject(j);
                    JSONObject t = c.optJSONObject("team");
                    String tName = t != null ? t.optString("displayName", "") : "";
                    String tId = t != null ? t.optString("id", "") : c.optString("id", "");
                    if (tId.equals("83") || tName.toLowerCase(Locale.US).contains("barcelona")) {
                        barcaComp = c;
                    } else {
                        oppComp = c;
                    }
                }

                if (barcaComp != null && oppComp != null) {
                    String barcaScore = extractScore(barcaComp);
                    String oppScore = extractScore(oppComp);
                    JSONObject oppTeam = oppComp.optJSONObject("team");
                    String oppAbbr = oppTeam != null ? oppTeam.optString("abbreviation", "OPP") : "OPP";
                    String competition = ev.optJSONObject("season") != null ? ev.optJSONObject("season").optString("displayName", "Match") : "Match";

                    boolean isLive = "in".equalsIgnoreCase(state);
                    return new LiveScore(isLive, completed, barcaScore, oppScore, oppAbbr, clock, competition);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extractScore(JSONObject comp) {
        Object sc = comp.opt("score");
        if (sc instanceof JSONObject) {
            return ((JSONObject) sc).optString("displayValue", "0");
        } else if (sc != null) {
            return String.valueOf(sc);
        }
        return "0";
    }
}
