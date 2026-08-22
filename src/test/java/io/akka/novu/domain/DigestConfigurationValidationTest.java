package io.akka.novu.domain;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SPEC-001 §3 rules V1-V7. */
class DigestConfigurationValidationTest {

    private static final String OK = "ok";

    private static String check(StepType step, DigestConfig config) {
        try {
            DigestConfigValidation.validate(step, config);

            return OK;
        } catch (DigestConfigurationException e) {
            return e.getMessage();
        }
    }

    @Test
    void onlyADigestStepAcceptsADigestConfiguration() {
        // V1. Every step type, because the rule is stated over the whole set.
        Map<StepType, String> outcomes = new LinkedHashMap<>();
        for (StepType step : StepType.values()) {
            outcomes.put(step, check(step, DigestConfig.builder(DigestKind.REGULAR)
                    .amount(5L).unit(DigestUnit.MINUTES).build()));
        }

        assertEquals(OK, outcomes.get(StepType.DIGEST));
        outcomes.forEach((step, outcome) -> {
            if (step != StepType.DIGEST) {
                assertEquals("Job is not a digest type", outcome, step + " should not accept one");
            }
        });
    }

    @Test
    void aDigestStepWithNoConfigurationAtAllIsRejected() {
        // V1's other half.
        assertEquals("Job is not a digest type", check(StepType.DIGEST, null));
    }

    @Test
    void aConfigurationNamingNoKindIsRejectedDifferently() {
        // V2 — a distinct message from V1's.
        assertEquals("Invalid digest metadata: missing type",
                check(StepType.DIGEST, DigestConfig.builder(DigestKind.UNTYPED)
                        .amount(5L).unit(DigestUnit.MINUTES).build()));
    }

    @Test
    void regularAndBackoffNeedANonZeroAmountAndAUnit() {
        // V3, including zero counting as absent.
        assertEquals(OK, check(StepType.DIGEST, DigestConfig.builder(DigestKind.REGULAR)
                .amount(5L).unit(DigestUnit.MINUTES).build()));
        assertEquals("Invalid digest amount", check(StepType.DIGEST,
                DigestConfig.builder(DigestKind.REGULAR).unit(DigestUnit.MINUTES).build()));
        assertEquals("Invalid digest amount", check(StepType.DIGEST,
                DigestConfig.builder(DigestKind.REGULAR).amount(0L).unit(DigestUnit.MINUTES).build()));
        assertEquals("Invalid digest unit", check(StepType.DIGEST,
                DigestConfig.builder(DigestKind.REGULAR).amount(5L).build()));
        assertEquals("Invalid digest amount", check(StepType.DIGEST,
                DigestConfig.builder(DigestKind.BACKOFF).unit(DigestUnit.MINUTES).build()));
    }

    @Test
    void aCronExpressionShortCircuitsEveryTimedCheck() {
        // V4-V7 are all skipped: no amount, no unit, no atTime.
        assertEquals(OK, check(StepType.DIGEST,
                DigestConfig.builder(DigestKind.TIMED).timed(TimedConfig.cron("0 9 * * *")).build()));
    }

    @Test
    void onlyTheCoarseTimedUnitsDemandATimeOfDay() {
        // V4. All six units.
        Map<DigestUnit, String> outcomes = new LinkedHashMap<>();
        for (DigestUnit unit : DigestUnit.values()) {
            outcomes.put(unit, check(StepType.DIGEST,
                    DigestConfig.builder(DigestKind.TIMED).amount(1L).unit(unit).build()));
        }

        assertEquals(OK, outcomes.get(DigestUnit.SECONDS));
        assertEquals(OK, outcomes.get(DigestUnit.MINUTES));
        assertEquals(OK, outcomes.get(DigestUnit.HOURS));
        assertEquals("Digest timed config is missing", outcomes.get(DigestUnit.DAYS));
        assertEquals("Digest timed config is missing", outcomes.get(DigestUnit.WEEKS));
        assertEquals("Digest timed config is missing", outcomes.get(DigestUnit.MONTHS));
    }

    @Test
    void whichTimeOfDayStringsAreAccepted() {
        // V5, and the absent case reported differently from the malformed one.
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (String atTime : List.of("09:00", "9:00", "00:00", "23:59", "23:59:59",
                "24:00", "09:60", "0900", "noon")) {
            outcomes.put(atTime, check(StepType.DIGEST, DigestConfig.builder(DigestKind.TIMED)
                    .amount(1L).unit(DigestUnit.DAYS).timed(TimedConfig.atTime(atTime)).build()));
        }

        assertEquals(OK, outcomes.get("09:00"));
        assertEquals(OK, outcomes.get("9:00"));
        assertEquals(OK, outcomes.get("00:00"));
        assertEquals(OK, outcomes.get("23:59"));
        assertEquals(OK, outcomes.get("23:59:59"));
        String malformed = "Digest timed config atTime has invalid format, expected 24h time format";
        assertEquals(malformed, outcomes.get("24:00"));
        assertEquals(malformed, outcomes.get("09:60"));
        assertEquals(malformed, outcomes.get("0900"));
        assertEquals(malformed, outcomes.get("noon"));
        assertEquals("Digest timed config atTime is missing", check(StepType.DIGEST,
                DigestConfig.builder(DigestKind.TIMED).amount(1L).unit(DigestUnit.DAYS)
                        .timed(TimedConfig.atTime("")).build()));
    }

    @Test
    void weeklyDemandsWeekDaysAndAnEmptyListCounts() {
        // V6, including the empty list being accepted.
        assertEquals("Digest timed config weekDays is missing", check(StepType.DIGEST,
                weekly(null)));
        assertEquals(OK, check(StepType.DIGEST, weekly(List.of())));
        for (DayOfWeek day : DayOfWeek.values()) {
            assertEquals(OK, check(StepType.DIGEST, weekly(List.of(day))), day.toString());
        }
    }

    @Test
    void monthlySplitsOnMonthlyType() {
        // V7, both branches and the absent-branch case.
        assertEquals("Digest timed config monthDays is missing",
                check(StepType.DIGEST, monthly(MonthlyType.EACH, null, null, null)));
        assertEquals(OK, check(StepType.DIGEST, monthly(MonthlyType.EACH, List.of(1, 31), null, null)));
        assertEquals("Digest timed config monthDays values are invalid",
                check(StepType.DIGEST, monthly(MonthlyType.EACH, List.of(0), null, null)));
        assertEquals("Digest timed config monthDays values are invalid",
                check(StepType.DIGEST, monthly(MonthlyType.EACH, List.of(32), null, null)));
        assertEquals("Digest timed config ordinal is missing",
                check(StepType.DIGEST, monthly(MonthlyType.ON, null, null, null)));
        assertEquals(OK, check(StepType.DIGEST,
                monthly(MonthlyType.ON, null, Ordinal.FIRST, OrdinalValue.MONDAY)));
        assertEquals("Digest timed config ordinal is missing",
                check(StepType.DIGEST, monthly(MonthlyType.ON, null, Ordinal.FIRST, null)));
        // No monthlyType at all matches neither branch, so nothing is demanded.
        assertEquals(OK, check(StepType.DIGEST, monthly(null, null, null, null)));
    }

    private static DigestConfig weekly(List<DayOfWeek> weekDays) {
        return DigestConfig.builder(DigestKind.TIMED).amount(1L).unit(DigestUnit.WEEKS)
                .timed(new TimedConfig("09:00", weekDays, List.of(), null, null, null, null)).build();
    }

    private static DigestConfig monthly(MonthlyType monthlyType, List<Integer> monthDays,
                                        Ordinal ordinal, OrdinalValue ordinalValue) {
        return DigestConfig.builder(DigestKind.TIMED).amount(1L).unit(DigestUnit.MONTHS)
                .timed(new TimedConfig("09:00", List.of(), monthDays, monthlyType, ordinal, ordinalValue, null))
                .build();
    }
}
