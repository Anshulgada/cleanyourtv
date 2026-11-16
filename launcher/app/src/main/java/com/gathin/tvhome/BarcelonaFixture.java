package com.gathin.tvhome;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** A provider-neutral Barça fixture. All timestamps remain UTC until rendered. */
final class BarcelonaFixture {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    final String id;
    final String opponent;
    final String competition;
    final String venue;
    final Instant kickoff;
    final boolean kickoffKnown;

    BarcelonaFixture(String id, String opponent, String competition, String venue, Instant kickoff,
                    boolean kickoffKnown) {
        this.id = id;
        this.opponent = opponent;
        this.competition = competition;
        this.venue = venue;
        this.kickoff = kickoff;
        this.kickoffKnown = kickoffKnown;
    }

    String dateAndTimeIst() {
        if (!kickoffKnown || kickoff == null) return "KICKOFF TIME TBA";
        ZonedDateTime time = kickoff.atZone(IST);
        return time.format(DateTimeFormatter.ofPattern("dd MMM · hh:mm a 'IST'", Locale.US));
    }

    String compactLabel(Instant now) {
        if (!kickoffKnown || kickoff == null) return "Barça • " + shortOpponent() + " • TBA";
        Duration left = Duration.between(now, kickoff);
        if (left.isNegative() || left.isZero()) {
            BarcelonaLiveScoreManager.LiveScore live = BarcelonaLiveScoreManager.getCurrentScore();
            if (live != null && (live.isLive || live.isCompleted)) {
                return live.formatLabel();
            }
            return "Barça • " + shortOpponent() + " • Live";
        }
        long seconds = left.getSeconds();
        if (seconds < 3600) return String.format(Locale.US, "Barça • %02dm %02ds", Math.max(0, seconds / 60), (seconds % 60));
        if (seconds < 24 * 3600) return String.format(Locale.US, "Barça • %02dh %02dm", (seconds / 3600), ((seconds % 3600) / 60));
        return "Barça • " + shortOpponent() + " • " + ((seconds + 86_399) / 86_400) + "d";
    }

    private String shortOpponent() {
        return opponent.length() <= 18 ? opponent : opponent.substring(0, 17).trim() + "…";
    }
}
