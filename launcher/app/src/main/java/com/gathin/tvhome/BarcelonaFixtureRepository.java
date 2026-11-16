package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, cache-first ICS client. The feed is fetched at most once every six hours while Home is visible;
 * no resident service, alarm, or API key is required.
 */
final class BarcelonaFixtureRepository {
    interface Callback { void onFixtures(List<BarcelonaFixture> fixtures, boolean refreshed); }
    private static final String URL_ICS = "https://matchcalendar.football/team/barcelona/barcelona.ics?src=team";
    private static final String PREFS = "barcelona_fixture_cache";
    private static final String KEY_ICS = "ics";
    private static final String KEY_SYNC = "sync";
    static final long STALE_MS = 6L * 60L * 60L * 1000L;

    private final Context context;
    private final SharedPreferences prefs;

    BarcelonaFixtureRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<BarcelonaFixture> cached() {
        String value = prefs.getString(KEY_ICS, null);
        if (value == null || value.length() == 0) value = bundled();
        return parse(value);
    }

    boolean isStale() { return System.currentTimeMillis() - prefs.getLong(KEY_SYNC, 0) >= STALE_MS; }

    void refresh(Callback callback) {
        new Thread(() -> {
            boolean refreshed = false;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(URL_ICS).openConnection();
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(9000);
                connection.setRequestProperty("Accept", "text/calendar");
                connection.setRequestProperty("User-Agent", "CleanYourTV-BarcaWidget/1.0");
                try (InputStream stream = connection.getInputStream()) {
                    String raw = read(stream);
                    List<BarcelonaFixture> parsed = parse(raw);
                    if (!parsed.isEmpty()) {
                        prefs.edit().putString(KEY_ICS, raw).putLong(KEY_SYNC, System.currentTimeMillis()).apply();
                        refreshed = true;
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
                // Keep the last validated cache untouched. Home must remain usable offline.
            }
            callback.onFixtures(cached(), refreshed);
        }, "barca-fixtures").start();
    }

    private String bundled() {
        try (InputStream stream = context.getResources().openRawResource(R.raw.barcelona_fixtures)) {
            return read(stream);
        } catch (Exception ignored) { return ""; }
    }

    private static String read(InputStream stream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder raw = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) raw.append(line).append('\n');
        return raw.toString();
    }

    /** Parses only validated Barcelona events. Folded ICS lines are unfolded before fields are read. */
    static List<BarcelonaFixture> parse(String calendar) {
        if (calendar == null) return Collections.emptyList();
        String unfolded = calendar.replaceAll("\\r?\\n[ \\t]", "");
        List<BarcelonaFixture> fixtures = new ArrayList<>();
        for (String event : unfolded.split("BEGIN:VEVENT")) {
            if (!event.contains("END:VEVENT")) continue;
            Map<String, String> fields = new HashMap<>();
            for (String line : event.split("\\r?\\n")) {
                int split = line.indexOf(':');
                if (split <= 0) continue;
                String key = line.substring(0, split);
                int param = key.indexOf(';');
                if (param >= 0) key = key.substring(0, param);
                fields.put(key, line.substring(split + 1).replace("\\n", " "));
            }
            String summary = fields.get("SUMMARY");
            String id = fields.get("UID");
            if (summary == null || id == null || !summary.toLowerCase().contains("barcelona")) continue;
            String opponent = opponent(summary);
            if (opponent.length() == 0) continue;
            String description = fields.get("DESCRIPTION");
            String competition = description == null ? "Match" : description.split(" - |\\n", 2)[0].trim();
            if (competition.length() == 0 || competition.equals(summary)) competition = competitionFromUrl(fields.get("URL"));
            Instant kickoff = parseUtc(fields.get("DTSTART"));
            String venue = fields.get("LOCATION");
            if (venue != null) venue = venue.replace("\\,", ",").replace("\\;", ";");
            fixtures.add(new BarcelonaFixture(id, opponent, competition, venue, kickoff, kickoff != null));
        }
        Collections.sort(fixtures, Comparator.comparing(f -> f.kickoff == null ? Instant.MAX : f.kickoff));
        return fixtures;
    }

    static BarcelonaFixture next(List<BarcelonaFixture> fixtures, Instant now) {
        for (BarcelonaFixture fixture : fixtures) {
            // A source that has not confirmed a final score cannot make us claim the match is live. Hold the
            // current fixture for its expected two-hour window, then advance safely to the next scheduled one.
            if (!fixture.kickoffKnown || !fixture.kickoff.plusSeconds(2 * 3600).isBefore(now)) return fixture;
        }
        return null;
    }

    private static String opponent(String summary) {
        String clean = summary.replaceAll("\\s+\\([^)]*\\)$", "").trim();
        // Completed events commonly arrive as "Barcelona 6-0 Alavés (FT)" rather than using v/vs.
        String[] scored = clean.split("\\s+\\d+[-–]\\d+\\s+", 2);
        if (scored.length == 2) return scored[0].equalsIgnoreCase("Barcelona") ? scored[1] : scored[0];
        String[] parts = clean.split("\\s+(?:v|vs\\.?|@|at)\\s+", 2);
        if (parts.length != 2) return "";
        return parts[0].equalsIgnoreCase("Barcelona") ? parts[1] : parts[0];
    }

    private static Instant parseUtc(String value) {
        if (value == null || !value.endsWith("Z")) return null;
        try {
            return LocalDateTime.parse(value.substring(0, value.length() - 1),
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) { return null; }
    }

    private static String competitionFromUrl(String value) {
        if (value == null) return "Match";
        if (value.contains("champions-league")) return "UEFA Champions League";
        if (value.contains("la-liga")) return "La Liga";
        if (value.contains("copa-del-rey")) return "Copa del Rey";
        return "Match";
    }
}
