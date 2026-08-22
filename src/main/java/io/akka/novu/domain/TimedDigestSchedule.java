package io.akka.novu.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * When a timed digest next fires — SPEC-001 §3 rule W7.
 *
 * <p>A timed digest is a schedule, not an offset: the answer is the interval from now to
 * the next occurrence of the schedule, so the same configuration gives a different number
 * depending on where in the cycle it is asked (question-log row 6).
 *
 * <p>The search is bounded the way the original bounds it: occurrences past
 * {@code anchor + amount × unit} are not occurrences, and a schedule with none inside that
 * bound is rejected rather than answered. That is why a daily rule filtered to one weekday
 * can fail — the bound is one day wide.
 */
public final class TimedDigestSchedule {

    private TimedDigestSchedule() {
    }

    public static long millisUntilNextOccurrence(DigestConfig config, Instant now, ZoneId zone) {
        TimedConfig timed = config.timed();
        if (timed != null && timed.hasCron()) {
            throw new DigestConfigurationException(
                    "Digest timed config carries a cron expression, which this engine does not schedule");
        }
        long amount = config.amount() == null ? 1L : config.amount();
        DigestUnit unit = config.unit() == null ? DigestUnit.MINUTES : config.unit();

        ZonedDateTime anchor = now.atZone(zone);
        ZonedDateTime until = anchor.plus(Duration.ofMillis(unit.times(amount)));
        ZonedDateTime next = firstOccurrenceAfter(anchor, until, amount, unit, timed);
        if (next == null) {
            throw new DigestConfigurationException("Delay for next digest could not be calculated");
        }

        return Duration.between(anchor, next).toMillis();
    }

    private static ZonedDateTime firstOccurrenceAfter(ZonedDateTime anchor, ZonedDateTime until,
                                                      long amount, DigestUnit unit, TimedConfig timed) {
        for (ZonedDateTime candidate : candidates(anchor, until, amount, unit, timed)) {
            if (candidate.isAfter(anchor) && !candidate.isAfter(until)) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Candidate occurrences in ascending order, generated only as far as the bound. The
     * generation walks by the digest's own frequency and then filters by the day rules,
     * which is what makes an over-filtered fine-grained schedule produce nothing.
     */
    private static List<ZonedDateTime> candidates(ZonedDateTime anchor, ZonedDateTime until,
                                                  long amount, DigestUnit unit, TimedConfig timed) {
        List<ZonedDateTime> found = new ArrayList<>();
        LocalTime timeOfDay = timeOfDay(timed, anchor);

        switch (unit) {
            case SECONDS, MINUTES, HOURS -> {
                ZonedDateTime step = anchor;
                while (!step.isAfter(until)) {
                    step = step.plus(Duration.ofMillis(unit.times(amount)));
                    if (dayMatches(step, timed)) {
                        found.add(step);
                    }
                }
            }
            case DAYS -> {
                LocalDate day = anchor.toLocalDate();
                while (true) {
                    ZonedDateTime at = ZonedDateTime.of(LocalDateTime.of(day, timeOfDay), anchor.getZone());
                    if (at.isAfter(until)) {
                        break;
                    }
                    if (!at.isBefore(anchor) && dayMatches(at, timed)) {
                        found.add(at);
                    }
                    day = day.plusDays(Math.max(1L, amount));
                }
            }
            case WEEKS -> {
                LocalDate weekStart = anchor.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                while (true) {
                    boolean anyInRange = false;
                    for (DayOfWeek day : weekDays(timed, anchor)) {
                        ZonedDateTime at = ZonedDateTime.of(
                                LocalDateTime.of(weekStart.with(TemporalAdjusters.nextOrSame(day)), timeOfDay),
                                anchor.getZone());
                        if (!at.isAfter(until)) {
                            anyInRange = true;
                        }
                        if (!at.isBefore(anchor) && !at.isAfter(until)) {
                            found.add(at);
                        }
                    }
                    if (!anyInRange && weekStart.atStartOfDay(anchor.getZone()).isAfter(until)) {
                        break;
                    }
                    if (weekStart.atStartOfDay(anchor.getZone()).isAfter(until)) {
                        break;
                    }
                    weekStart = weekStart.plusWeeks(Math.max(1L, amount));
                }
            }
            case MONTHS -> {
                LocalDate monthStart = anchor.toLocalDate().withDayOfMonth(1);
                while (!monthStart.atStartOfDay(anchor.getZone()).isAfter(until)) {
                    for (LocalDate day : monthDays(timed, monthStart, anchor)) {
                        ZonedDateTime at = ZonedDateTime.of(LocalDateTime.of(day, timeOfDay), anchor.getZone());
                        if (!at.isBefore(anchor) && !at.isAfter(until)) {
                            found.add(at);
                        }
                    }
                    monthStart = monthStart.plusMonths(Math.max(1L, amount));
                }
            }
        }
        found.sort(ZonedDateTime::compareTo);

        return found;
    }

    /** The stated time of day, or the anchor's own where none was stated. */
    private static LocalTime timeOfDay(TimedConfig timed, ZonedDateTime anchor) {
        if (timed == null || timed.atTime() == null || timed.atTime().isBlank()) {
            return anchor.toLocalTime();
        }
        String[] parts = timed.atTime().split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int second = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

        return LocalTime.of(hour, minute, second);
    }

    /** A day filter applied to a finer-grained schedule; no stated days means no filter. */
    private static boolean dayMatches(ZonedDateTime candidate, TimedConfig timed) {
        if (timed == null || timed.weekDays() == null || timed.weekDays().isEmpty()) {
            return true;
        }

        return timed.weekDays().contains(candidate.getDayOfWeek());
    }

    private static List<DayOfWeek> weekDays(TimedConfig timed, ZonedDateTime anchor) {
        if (timed == null || timed.weekDays() == null || timed.weekDays().isEmpty()) {
            return List.of(anchor.getDayOfWeek());
        }

        return timed.weekDays();
    }

    private static List<LocalDate> monthDays(TimedConfig timed, LocalDate monthStart, ZonedDateTime anchor) {
        if (timed != null && timed.monthlyType() == MonthlyType.ON && timed.ordinal() != null
                && timed.ordinalValue() != null) {
            LocalDate ordinal = ordinalDay(monthStart, timed.ordinal(), timed.ordinalValue());

            return ordinal == null ? List.of() : List.of(ordinal);
        }
        List<Integer> stated = timed == null ? null : timed.monthDays();
        if (stated == null || stated.isEmpty()) {
            stated = List.of(anchor.getDayOfMonth());
        }
        List<LocalDate> days = new ArrayList<>();
        int length = monthStart.lengthOfMonth();
        for (int dayOfMonth : stated) {
            if (dayOfMonth >= 1 && dayOfMonth <= length) {
                days.add(monthStart.withDayOfMonth(dayOfMonth));
            }
        }

        return days;
    }

    /** Null where the month has no such occurrence, such as a fifth Tuesday it does not have. */
    private static LocalDate ordinalDay(LocalDate monthStart, Ordinal ordinal, OrdinalValue value) {
        List<LocalDate> matching = new ArrayList<>();
        for (int day = 1; day <= monthStart.lengthOfMonth(); day++) {
            LocalDate date = monthStart.withDayOfMonth(day);
            if (matchesOrdinalValue(date, value)) {
                matching.add(date);
            }
        }
        if (matching.isEmpty()) {
            return null;
        }
        int index = switch (ordinal) {
            case FIRST -> 0;
            case SECOND -> 1;
            case THIRD -> 2;
            case FOURTH -> 3;
            case FIFTH -> 4;
            case LAST -> matching.size() - 1;
        };

        return index < matching.size() ? matching.get(index) : null;
    }

    private static boolean matchesOrdinalValue(LocalDate date, OrdinalValue value) {
        DayOfWeek day = date.getDayOfWeek();

        return switch (value) {
            case DAY -> true;
            case WEEKDAY -> day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
            case WEEKEND -> day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
            default -> day == value.asDayOfWeek();
        };
    }
}
