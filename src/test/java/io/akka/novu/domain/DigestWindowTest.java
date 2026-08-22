package io.akka.novu.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SPEC-001 §3 rules W1-W6, W8. */
class DigestWindowTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Test
    void regularWindowScalesByUnit() {
        // W1. Every unit, because the rule is stated over the whole set.
        Map<DigestUnit, Long> perUnitAtOne = Map.of(
                DigestUnit.SECONDS, 1_000L,
                DigestUnit.MINUTES, 60_000L,
                DigestUnit.HOURS, 3_600_000L,
                DigestUnit.DAYS, 86_400_000L,
                DigestUnit.WEEKS, 604_800_000L,
                DigestUnit.MONTHS, 2_592_000_000L);

        for (DigestUnit unit : DigestUnit.values()) {
            assertEquals(perUnitAtOne.get(unit),
                    DigestWindow.of(regular(1, unit), Map.of(), null, NOW, ZoneOffset.UTC),
                    "amount 1 in " + unit);
            assertEquals(perUnitAtOne.get(unit) * 3,
                    DigestWindow.of(regular(3, unit), Map.of(), null, NOW, ZoneOffset.UTC),
                    "amount 3 in " + unit);
        }
    }

    @Test
    void aMonthIsAFlatThirtyDays() {
        // W1. Stated separately because it is the one unit that is not what its name says.
        assertEquals(30L * 86_400_000L,
                DigestWindow.of(regular(1, DigestUnit.MONTHS), Map.of(), null, NOW, ZoneOffset.UTC));
    }

    @Test
    void backoffWindowMatchesRegular() {
        // W2.
        DigestConfig backoff = DigestConfig.builder(DigestKind.BACKOFF)
                .amount(7L).unit(DigestUnit.MINUTES)
                .backoff(true).backoffAmount(3L).backoffUnit(DigestUnit.MINUTES)
                .build();

        assertEquals(DigestWindow.of(regular(7, DigestUnit.MINUTES), Map.of(), null, NOW, ZoneOffset.UTC),
                DigestWindow.of(backoff, Map.of(), null, NOW, ZoneOffset.UTC));
    }

    @Test
    void overrideReplacesAmountAndUnit() {
        // W3.
        assertEquals(2 * 3_600_000L,
                DigestWindow.of(regular(5, DigestUnit.MINUTES), Map.of(),
                        new DelayOverride(2, "hours"), NOW, ZoneOffset.UTC));
    }

    @Test
    void malformedOverrideIsIgnored() {
        // W4. Both halves: a unit outside the six, and an amount that is not a number.
        assertEquals(5 * 60_000L,
                DigestWindow.of(regular(5, DigestUnit.MINUTES), Map.of(),
                        new DelayOverride(2, "fortnights"), NOW, ZoneOffset.UTC));
        assertEquals(5 * 60_000L,
                DigestWindow.of(regular(5, DigestUnit.MINUTES), Map.of(),
                        new DelayOverride("2", "hours"), NOW, ZoneOffset.UTC));
    }

    @Test
    void missingConfigurationIsRejected() {
        // W5.
        assertThrows(DigestConfigurationException.class,
                () -> DigestWindow.of(null, Map.of(), null, NOW, ZoneOffset.UTC));
    }

    @Test
    void unrecognisedConfigurationIsZero() {
        // W6. A configuration with no kind and no amount/unit answers zero rather than raising.
        assertEquals(0L, DigestWindow.of(DigestConfig.builder(DigestKind.UNTYPED).build(),
                Map.of(), null, NOW, ZoneOffset.UTC));
    }

    @Test
    void untypedConfigurationCarryingAmountAndUnitIsStillConverted() {
        // W6's other half.
        DigestConfig untyped = DigestConfig.builder(DigestKind.UNTYPED)
                .amount(2L).unit(DigestUnit.MINUTES).build();
        assertEquals(120_000L, DigestWindow.of(untyped, Map.of(), null, NOW, ZoneOffset.UTC));
    }

    @Test
    void untypedConfigurationWithZeroAmountIsZero() {
        DigestConfig untyped = DigestConfig.builder(DigestKind.UNTYPED)
                .amount(0L).unit(DigestUnit.HOURS).build();
        assertEquals(0L, DigestWindow.of(untyped, Map.of(), null, NOW, ZoneOffset.UTC));
    }

    @Test
    void scheduledDelayIsFutureOnly() {
        // W8.
        DigestConfig scheduled = DigestConfig.builder(DigestKind.SCHEDULED).delayPath("sendAt").build();

        assertEquals(30 * 60_000L,
                DigestWindow.of(scheduled, Map.of("sendAt", "2026-03-10T12:30:00Z"), null, NOW, ZoneOffset.UTC));

        assertThrows(DigestConfigurationException.class,
                () -> DigestWindow.of(scheduled, Map.of("sendAt", "2026-03-10T11:30:00Z"), null, NOW, ZoneOffset.UTC));
    }

    @Test
    void scheduledDelayWithNoPathIsRejected() {
        assertThrows(DigestConfigurationException.class,
                () -> DigestWindow.of(DigestConfig.builder(DigestKind.SCHEDULED).build(),
                        Map.of("sendAt", "2026-03-10T12:30:00Z"), null, NOW, ZoneOffset.UTC));
    }

    private static DigestConfig regular(int amount, DigestUnit unit) {
        return DigestConfig.builder(DigestKind.REGULAR).amount((long) amount).unit(unit).build();
    }
}
