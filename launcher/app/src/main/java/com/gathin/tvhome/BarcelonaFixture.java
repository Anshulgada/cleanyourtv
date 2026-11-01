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
        if (left.isNegative() || left.isZero()) return "MATCH IN PROGRESS";
        long seconds = left.getSeconds();
        if (seconds < 3600) return "Barça • " + Math.max(0, seconds / 60) + "m " + (seconds % 60) + "s";
        if (seconds < 24 * 3600) return "Barça • " + (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        return "Barça • " + shortOpponent() + " • " + ((seconds + 86_399) / 86_400) + "d";
    }

    private String shortOpponent() {
        return opponent.length() <= 18 ? opponent : opponent.substring(0, 17).trim() + "…";
    }
}
