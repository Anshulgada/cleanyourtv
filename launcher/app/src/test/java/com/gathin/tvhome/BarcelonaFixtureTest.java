package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.time.Instant;
import java.util.List;

import org.junit.Test;

public class BarcelonaFixtureTest {
    private static final String ICS = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:old\nDTSTART:20261031T200000Z\n"
            + "SUMMARY:Barcelona 6-0 Alavés (FT)\nDESCRIPTION:La Liga - Round 11\nEND:VEVENT\n"
            + "BEGIN:VEVENT\nUID:next\nDTSTART:20261103T200000Z\nSUMMARY:Barcelona v Aston Villa\n"
            + "DESCRIPTION:UEFA Champions League - League Stage - 4\nLOCATION:Camp Nou\\, Barcelona\nEND:VEVENT\nEND:VCALENDAR\n";

    @Test public void parsesUpcomingHomeFixtureAndCompetition() {
        List<BarcelonaFixture> fixtures = BarcelonaFixtureRepository.parse(ICS);
        assertEquals(2, fixtures.size());
        assertEquals("Aston Villa", fixtures.get(1).opponent);
        assertEquals("UEFA Champions League", fixtures.get(1).competition);
    }

    @Test public void convertsUtcToIstWithoutDeviceTimezone() {
        BarcelonaFixture fixture = BarcelonaFixtureRepository.parse(ICS).get(1);
        assertEquals("04 Nov · 01:30 AM IST", fixture.dateAndTimeIst());
    }

    @Test public void selectsNextAfterFinishedWindow() {
        BarcelonaFixture fixture = BarcelonaFixtureRepository.next(BarcelonaFixtureRepository.parse(ICS),
                Instant.parse("2026-11-01T12:00:00Z"));
        assertNotNull(fixture);
        assertEquals("Aston Villa", fixture.opponent);
    }

    @Test public void finalHourUsesCompactCountdown() {
        BarcelonaFixture fixture = BarcelonaFixtureRepository.parse(ICS).get(1);
        assertEquals("Barça • 42m 00s", fixture.compactLabel(Instant.parse("2026-11-03T19:18:00Z")));
    }
}
