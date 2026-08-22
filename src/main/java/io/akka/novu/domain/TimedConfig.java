package io.akka.novu.domain;

import java.time.DayOfWeek;
import java.util.List;

/**
 * The schedule half of a timed digest — SPEC-001 §2, §3 rules V4-V7, W7.
 *
 * @param atTime time of day, {@code H:mm}, {@code HH:mm} or {@code HH:mm:ss}
 * @param weekDays days a weekly digest fires on; an empty list is a stated empty list and
 *     is accepted, a null one is an absent field and is not (rule V6)
 * @param monthDays days of month a monthly {@code EACH} digest fires on, 1-31
 * @param monthlyType which of the two monthly shapes this is; null matches neither branch
 *     and demands nothing (rule V7)
 * @param ordinal which occurrence within the month, for {@code ON}
 * @param ordinalValue what that occurrence is an occurrence of, for {@code ON}
 * @param cronExpression short-circuits every other timed check (rule W9)
 */
public record TimedConfig(
        String atTime,
        List<DayOfWeek> weekDays,
        List<Integer> monthDays,
        MonthlyType monthlyType,
        Ordinal ordinal,
        OrdinalValue ordinalValue,
        String cronExpression) {

    public static TimedConfig atTime(String atTime) {
        return new TimedConfig(atTime, List.of(), List.of(), null, null, null, null);
    }

    public static TimedConfig cron(String cronExpression) {
        return new TimedConfig(null, List.of(), List.of(), null, null, null, cronExpression);
    }

    public boolean hasCron() {
        return cronExpression != null && !cronExpression.isBlank();
    }
}
