package io.akka.novu.domain;

import java.time.DayOfWeek;

/**
 * What an {@link Ordinal} counts — SPEC-001 §3 rule V7.
 *
 * <p>{@link #DAY} counts calendar days, {@link #WEEKDAY} counts Monday to Friday,
 * {@link #WEEKEND} counts Saturday and Sunday, and the seven named members count that
 * one day of the week.
 */
public enum OrdinalValue {
    DAY,
    WEEKDAY,
    WEEKEND,
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY;

    /** Null for the three that are not a single named day. */
    public DayOfWeek asDayOfWeek() {
        return switch (this) {
            case MONDAY -> DayOfWeek.MONDAY;
            case TUESDAY -> DayOfWeek.TUESDAY;
            case WEDNESDAY -> DayOfWeek.WEDNESDAY;
            case THURSDAY -> DayOfWeek.THURSDAY;
            case FRIDAY -> DayOfWeek.FRIDAY;
            case SATURDAY -> DayOfWeek.SATURDAY;
            case SUNDAY -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }
}
