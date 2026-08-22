package io.akka.novu.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC-001 §3 rule W7 — a timed digest waits for the next occurrence, not a fixed offset. */
class TimedDigestScheduleTest {

    @Test
    void dailyPicksNextOccurrence() {
        Instant eight = Instant.parse("2026-03-10T08:00:00Z");
        Instant ten = Instant.parse("2026-03-10T10:00:00Z");
        DigestConfig daily = timed(1, DigestUnit.DAYS, TimedConfig.atTime("09:00"));

        assertEquals(3_600_000L, DigestWindow.of(daily, java.util.Map.of(), null, eight, ZoneOffset.UTC));
        assertEquals(23 * 3_600_000L, DigestWindow.of(daily, java.util.Map.of(), null, ten, ZoneOffset.UTC));
    }

    @Test
    void weeklyPicksNamedDay() {
        // 2026-03-10 is a Tuesday; the next Friday 09:00 is three days and one hour away.
        Instant tuesday = Instant.parse("2026-03-10T08:00:00Z");
        DigestConfig weekly = timed(1, DigestUnit.WEEKS,
                new TimedConfig("09:00", List.of(java.time.DayOfWeek.FRIDAY), List.of(), null, null, null, null));

        assertEquals((3 * 24 + 1) * 3_600_000L,
                DigestWindow.of(weekly, java.util.Map.of(), null, tuesday, ZoneOffset.UTC));
    }

    @Test
    void monthlyPicksNamedDayOfMonth() {
        Instant tenth = Instant.parse("2026-03-10T00:00:00Z");
        DigestConfig monthly = timed(1, DigestUnit.MONTHS,
                new TimedConfig("00:00", List.of(), List.of(1), MonthlyType.EACH, null, null, null));

        assertEquals(22L * 86_400_000L,
                DigestWindow.of(monthly, java.util.Map.of(), null, tenth, ZoneOffset.UTC));
    }

    @Test
    void monthlyOrdinalPicksTheNthNamedWeekday() {
        // The second Tuesday of March 2026 is the 10th, nine days after the first.
        Instant firstOfMarch = Instant.parse("2026-03-01T00:00:00Z");
        DigestConfig monthly = timed(1, DigestUnit.MONTHS,
                new TimedConfig("00:00", List.of(), List.of(), MonthlyType.ON,
                        Ordinal.SECOND, OrdinalValue.TUESDAY, null));

        assertEquals(9L * 86_400_000L,
                DigestWindow.of(monthly, java.util.Map.of(), null, firstOfMarch, ZoneOffset.UTC));
    }

    @Test
    void anIntervalWiderThanOneSkipsTheMonthsBetween() {
        // At interval two the next candidate month is May, whose second Tuesday falls past
        // the schedule's own bound of sixty days, so there is no occurrence to wait for.
        Instant tenthOfMarch = Instant.parse("2026-03-10T00:00:00Z");
        DigestConfig monthly = timed(2, DigestUnit.MONTHS,
                new TimedConfig("00:00", List.of(), List.of(), MonthlyType.ON,
                        Ordinal.SECOND, OrdinalValue.TUESDAY, null));

        assertThrows(DigestConfigurationException.class,
                () -> DigestWindow.of(monthly, java.util.Map.of(), null, tenthOfMarch, ZoneOffset.UTC));
    }

    @Test
    void anOccurrenceLandingExactlyOnTheAnchorIsNotTheNextOne() {
        // Asked at exactly the hour it fires, a daily digest waits a whole day rather than
        // firing immediately.
        Instant nine = Instant.parse("2026-03-10T09:00:00Z");
        DigestConfig daily = timed(1, DigestUnit.DAYS, TimedConfig.atTime("09:00"));

        assertEquals(86_400_000L, DigestWindow.of(daily, java.util.Map.of(), null, nine, ZoneOffset.UTC));
    }

    @Test
    void monthlyOrdinalLastDayIsTheLastOfTheMonth() {
        Instant tenthOfMarch = Instant.parse("2026-03-10T00:00:00Z");
        DigestConfig monthly = timed(1, DigestUnit.MONTHS,
                new TimedConfig("00:00", List.of(), List.of(), MonthlyType.ON,
                        Ordinal.LAST, OrdinalValue.DAY, null));

        assertEquals(21L * 86_400_000L,
                DigestWindow.of(monthly, java.util.Map.of(), null, tenthOfMarch, ZoneOffset.UTC));
    }

    @Test
    void unreachableOccurrenceIsRejected() {
        // W7's second half: a daily schedule filtered to Sundays has no occurrence inside
        // its own one-day bound, so the answer is a rejection rather than a number.
        Instant tuesday = Instant.parse("2026-03-10T10:00:00Z");
        DigestConfig daily = timed(1, DigestUnit.DAYS,
                new TimedConfig("09:00", List.of(java.time.DayOfWeek.SUNDAY), List.of(), null, null, null, null));

        assertThrows(DigestConfigurationException.class,
                () -> DigestWindow.of(daily, java.util.Map.of(), null, tuesday, ZoneOffset.UTC));
    }

    @Test
    void theZoneDecidesWhenTheTimeOfDayFalls() {
        // 2026-03-10 is inside daylight saving in New York, so 09:00 there is 13:00 UTC and
        // the wait from 08:00 UTC is five hours rather than one. The original raises for
        // this combination (question-log row 33); SPEC-001 open decision OD-5 is why the
        // port answers it.
        Instant eight = Instant.parse("2026-03-10T08:00:00Z");
        DigestConfig daily = timed(1, DigestUnit.DAYS, TimedConfig.atTime("09:00"));

        assertEquals(3_600_000L, DigestWindow.of(daily, java.util.Map.of(), null, eight, ZoneOffset.UTC));
        assertEquals(5 * 3_600_000L,
                DigestWindow.of(daily, java.util.Map.of(), null, eight, ZoneId.of("America/New_York")));
    }

    @Test
    void everyZoneAndTimeOfDayCombinationAnswers() {
        // The original raises for sixteen of these thirty combinations and answers fourteen
        // (question-log row 33). The port answers all thirty, which is what OD-5 chose; this
        // check is what makes the choice visible rather than incidental.
        Instant eight = Instant.parse("2026-03-10T08:00:00Z");
        Instant twenty = Instant.parse("2026-03-10T20:00:00Z");
        int answered = 0;
        for (ZoneId zone : List.of(ZoneOffset.UTC, ZoneId.of("UTC"), ZoneId.of("America/New_York"),
                ZoneId.of("Europe/Berlin"), ZoneId.of("Asia/Tokyo"))) {
            for (String atTime : List.of("00:00", "09:00", "23:00")) {
                for (Instant asked : List.of(eight, twenty)) {
                    DigestConfig daily = timed(1, DigestUnit.DAYS, TimedConfig.atTime(atTime));
                    long waited = DigestWindow.of(daily, java.util.Map.of(), null, asked, zone);
                    assertTrue(waited > 0 && waited <= 86_400_000L,
                            zone + " at " + atTime + " asked " + asked + " gave " + waited);
                    answered++;
                }
            }
        }
        assertEquals(30, answered);
    }

    @Test
    void aCronExpressionIsNotComputedHere() {
        // W9's other side: the window layer refuses a cron digest rather than guessing at
        // one, because the source hands cron digests to a different scheduler entirely.
        DigestConfig cron = timed(1, DigestUnit.DAYS, TimedConfig.cron("0 9 * * *"));

        assertThrows(DigestConfigurationException.class,
                () -> DigestWindow.of(cron, java.util.Map.of(), null,
                        Instant.parse("2026-03-10T08:00:00Z"), ZoneOffset.UTC));
    }

    private static DigestConfig timed(int amount, DigestUnit unit, TimedConfig timed) {
        return DigestConfig.builder(DigestKind.TIMED).amount((long) amount).unit(unit).timed(timed).build();
    }
}
