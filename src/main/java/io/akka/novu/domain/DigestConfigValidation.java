package io.akka.novu.domain;

import java.time.DayOfWeek;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a digest configuration must carry before anything acts on it — SPEC-001 §3 rules
 * V1-V7.
 *
 * <p>The messages are part of the contract, not decoration: a caller distinguishes an
 * absent time of day from a badly formatted one by which of the two it gets back
 * (question-log row 12).
 */
public final class DigestConfigValidation {

    private static final Pattern AT_TIME = Pattern.compile("^([01]?[0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$");

    private DigestConfigValidation() {
    }

    public static void validate(StepType step, DigestConfig config) {
        if (config == null || step != StepType.DIGEST) {
            throw new DigestConfigurationException("Job is not a digest type");
        }
        if (config.kind() == DigestKind.UNTYPED) {
            throw new DigestConfigurationException("Invalid digest metadata: missing type");
        }
        if (config.isRegularLike()) {
            validateAmountAndUnit(config);
        }
        if (config.kind() == DigestKind.TIMED) {
            validateTimed(config);
        }
    }

    /** Zero counts as absent, because the check the original makes is truthiness (rule V3). */
    private static void validateAmountAndUnit(DigestConfig config) {
        if (config.amount() == null || config.amount() == 0L) {
            throw new DigestConfigurationException("Invalid digest amount");
        }
        if (config.unit() == null) {
            throw new DigestConfigurationException("Invalid digest unit");
        }
    }

    private static void validateTimed(DigestConfig config) {
        TimedConfig timed = config.timed();
        if (timed != null && timed.hasCron()) {
            return;
        }
        validateAmountAndUnit(config);

        switch (config.unit()) {
            case DAYS, WEEKS, MONTHS -> {
                if (timed == null) {
                    throw new DigestConfigurationException("Digest timed config is missing");
                }
                validateAtTime(timed.atTime());
                if (config.unit() == DigestUnit.WEEKS) {
                    validateWeekDays(timed.weekDays());
                }
                if (config.unit() == DigestUnit.MONTHS && timed.monthlyType() == MonthlyType.EACH) {
                    validateMonthDays(timed.monthDays());
                }
                if (config.unit() == DigestUnit.MONTHS && timed.monthlyType() == MonthlyType.ON) {
                    validateOrdinal(timed);
                }
            }
            default -> {
                // Seconds, minutes and hours name a time of day nowhere, so there is nothing
                // further to demand of them.
            }
        }
    }

    private static void validateAtTime(String atTime) {
        if (atTime == null || atTime.isEmpty()) {
            throw new DigestConfigurationException("Digest timed config atTime is missing");
        }
        if (!AT_TIME.matcher(atTime).matches()) {
            throw new DigestConfigurationException(
                    "Digest timed config atTime has invalid format, expected 24h time format");
        }
    }

    /** An empty list is a stated empty list and passes; only an absent one is missing. */
    private static void validateWeekDays(List<DayOfWeek> weekDays) {
        if (weekDays == null) {
            throw new DigestConfigurationException("Digest timed config weekDays is missing");
        }
        if (weekDays.stream().anyMatch(java.util.Objects::isNull)) {
            throw new DigestConfigurationException("Digest timed config weekDays has invalid values");
        }
    }

    private static void validateMonthDays(List<Integer> monthDays) {
        if (monthDays == null) {
            throw new DigestConfigurationException("Digest timed config monthDays is missing");
        }
        if (monthDays.stream().anyMatch(day -> day == null || day < 1 || day > 31)) {
            throw new DigestConfigurationException("Digest timed config monthDays values are invalid");
        }
    }

    private static void validateOrdinal(TimedConfig timed) {
        if (timed.ordinal() == null || timed.ordinalValue() == null) {
            throw new DigestConfigurationException("Digest timed config ordinal is missing");
        }
    }
}
