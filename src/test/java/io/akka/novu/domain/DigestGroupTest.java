package io.akka.novu.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC-001 §3 rules G1-G7. */
class DigestGroupTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private static final DigestConfig REGULAR = DigestConfig.builder(DigestKind.REGULAR)
            .amount(5L).unit(DigestUnit.MINUTES).digestKey("orderId").digestValue("order-7").build();

    private static DigestEvent event(String id, Instant createdAt) {
        return new DigestEvent(id, "notif-" + id, createdAt, Map.of("orderId", "order-7"));
    }

    @Test
    void firstEventOpensTheDigest() {
        // G1.
        DigestGroup.Decision decision =
                DigestGroup.offer(DigestGroupState.empty(), event("e1", NOW), REGULAR, NOW, ZoneOffset.UTC);

        assertEquals(DigestOutcome.CREATED, decision.outcome());
        assertEquals("e1", decision.state().masterEventId());
        assertTrue(decision.state().open());
        assertEquals(NOW.plusMillis(5 * 60_000L), decision.state().deadline());
    }

    @Test
    void laterEventMerges() {
        // G2.
        DigestGroupState opened = DigestGroup
                .offer(DigestGroupState.empty(), event("e1", NOW), REGULAR, NOW, ZoneOffset.UTC).state();
        DigestGroup.Decision second = DigestGroup
                .offer(opened, event("e2", NOW.plusSeconds(30)), REGULAR, NOW.plusSeconds(30), ZoneOffset.UTC);

        assertEquals(DigestOutcome.MERGED, second.outcome());
        assertEquals("e1", second.state().masterEventId(), "the master does not move");
        assertEquals("e1", second.mergedInto());
        assertEquals(NOW.plusMillis(5 * 60_000L), second.state().deadline(), "the deadline does not move");
        assertEquals(2, second.state().events().size());
    }

    @Test
    void onlyAnOpenDigestMerges() {
        // G3's second half. A group whose digest has already been delivered opens a new one.
        DigestGroupState opened = DigestGroup
                .offer(DigestGroupState.empty(), event("e1", NOW), REGULAR, NOW, ZoneOffset.UTC).state();
        DigestGroupState delivered = DigestGroup.deliver(opened).state();

        DigestGroup.Decision next = DigestGroup
                .offer(delivered, event("e2", NOW.plusSeconds(600)), REGULAR, NOW.plusSeconds(600), ZoneOffset.UTC);

        assertEquals(DigestOutcome.CREATED, next.outcome());
        assertEquals("e2", next.state().masterEventId());
        assertNull(next.mergedInto());
    }

    @Test
    void groupingKeyIsFourFields() {
        // G3's first half. The key is the identity of the group, so two events group together
        // exactly when all four parts match. Each part is varied in turn.
        DigestGroupKey base = new DigestGroupKey("env-1", "tpl-1", "sub-1", "order-7");

        Map<String, DigestGroupKey> varied = new LinkedHashMap<>();
        varied.put("environment", new DigestGroupKey("env-2", "tpl-1", "sub-1", "order-7"));
        varied.put("workflow", new DigestGroupKey("env-1", "tpl-2", "sub-1", "order-7"));
        varied.put("subscriber", new DigestGroupKey("env-1", "tpl-1", "sub-2", "order-7"));
        varied.put("digestValue", new DigestGroupKey("env-1", "tpl-1", "sub-1", "order-8"));

        varied.forEach((field, other) ->
                assertFalse(base.id().equals(other.id()), field + " should not share a group"));
        assertEquals(base.id(), new DigestGroupKey("env-1", "tpl-1", "sub-1", "order-7").id());
    }

    @Test
    void anAbsentDigestValueIsAValueOfItsOwn() {
        // A workflow with no digest key groups all of a subscriber's events together, and that
        // group is distinct from one whose digest value happens to be the empty string.
        assertEquals(new DigestGroupKey("env-1", "tpl-1", "sub-1", null).id(),
                new DigestGroupKey("env-1", "tpl-1", "sub-1", null).id());
        assertFalse(new DigestGroupKey("env-1", "tpl-1", "sub-1", null).id()
                .equals(new DigestGroupKey("env-1", "tpl-1", "sub-1", "").id()));
    }

    @Test
    void backoffRecognisedThreeWays() {
        // G4. Each of the three markers alone puts the event on the backoff path, which with
        // no earlier event in the look-back means SKIPPED rather than CREATED.
        DigestConfig byKind = DigestConfig.builder(DigestKind.BACKOFF).amount(5L).unit(DigestUnit.MINUTES)
                .backoffAmount(10L).backoffUnit(DigestUnit.MINUTES).digestValue("order-7").build();
        DigestConfig byFlag = DigestConfig.builder(DigestKind.REGULAR).amount(5L).unit(DigestUnit.MINUTES)
                .backoff(true).backoffAmount(10L).backoffUnit(DigestUnit.MINUTES).digestValue("order-7").build();
        DigestConfig byBoth = DigestConfig.builder(DigestKind.BACKOFF).amount(5L).unit(DigestUnit.MINUTES)
                .backoff(true).backoffAmount(10L).backoffUnit(DigestUnit.MINUTES).digestValue("order-7").build();

        for (DigestConfig config : java.util.List.of(byKind, byFlag, byBoth)) {
            assertEquals(DigestOutcome.SKIPPED,
                    DigestGroup.offer(DigestGroupState.empty(), event("e1", NOW), config, NOW, ZoneOffset.UTC)
                            .outcome());
        }
    }

    @Test
    void loneEventSkipsTheDigest() {
        // G5.
        DigestGroup.Decision decision = DigestGroup
                .offer(DigestGroupState.empty(), event("e1", NOW), backoff(), NOW, ZoneOffset.UTC);

        assertEquals(DigestOutcome.SKIPPED, decision.outcome());
        assertFalse(decision.state().open(), "a skipped event opens nothing");
    }

    @Test
    void earliestEventSkips() {
        // G6. An event that precedes the earliest other event in the look-back is skipped even
        // though the look-back is not empty.
        DigestGroupState afterFirst = DigestGroup
                .offer(DigestGroupState.empty(), event("e1", NOW), backoff(), NOW, ZoneOffset.UTC).state();

        DigestGroup.Decision earlier = DigestGroup.offer(afterFirst,
                event("e0", NOW.minusSeconds(60)), backoff(), NOW.plusSeconds(1), ZoneOffset.UTC);

        assertEquals(DigestOutcome.SKIPPED, earlier.outcome());
    }

    @Test
    void followingEventOpensTheDigest() {
        // G6's other half.
        DigestGroupState afterFirst = DigestGroup
                .offer(DigestGroupState.empty(), event("e1", NOW), backoff(), NOW, ZoneOffset.UTC).state();

        DigestGroup.Decision second = DigestGroup.offer(afterFirst,
                event("e2", NOW.plusSeconds(60)), backoff(), NOW.plusSeconds(60), ZoneOffset.UTC);

        assertEquals(DigestOutcome.CREATED, second.outcome());
        assertEquals("e2", second.state().masterEventId());
    }

    @Test
    void anEventOutsideTheLookBackDoesNotCount() {
        // G5. The earlier event is real but too old, so the look-back is empty again.
        DigestGroupState afterFirst = DigestGroup
                .offer(DigestGroupState.empty(), event("e1", NOW), backoff(), NOW, ZoneOffset.UTC).state();

        Instant muchLater = NOW.plusSeconds(60 * 60);
        DigestGroup.Decision second = DigestGroup
                .offer(afterFirst, event("e2", muchLater), backoff(), muchLater, ZoneOffset.UTC);

        assertEquals(DigestOutcome.SKIPPED, second.outcome());
    }

    @Test
    void aThirdEventMergesIntoTheBackoffDigest() {
        // The whole backoff sequence: skip, create, merge. A rule about what happens next time
        // is not shown by one event, so the sequence is the unit of comparison.
        DigestGroupState s0 = DigestGroupState.empty();
        DigestGroup.Decision d1 = DigestGroup.offer(s0, event("e1", NOW), backoff(), NOW, ZoneOffset.UTC);
        DigestGroup.Decision d2 = DigestGroup.offer(d1.state(), event("e2", NOW.plusSeconds(60)),
                backoff(), NOW.plusSeconds(60), ZoneOffset.UTC);
        DigestGroup.Decision d3 = DigestGroup.offer(d2.state(), event("e3", NOW.plusSeconds(120)),
                backoff(), NOW.plusSeconds(120), ZoneOffset.UTC);

        assertEquals(java.util.List.of(DigestOutcome.SKIPPED, DigestOutcome.CREATED, DigestOutcome.MERGED),
                java.util.List.of(d1.outcome(), d2.outcome(), d3.outcome()));
    }

    @Test
    void invalidRegularConfigurationRejectedAfterTheMark() {
        // G7. The rejection is observable, and so is the mark that precedes it: a caller that
        // reads the group afterwards sees a digest opened with nothing behind it.
        DigestConfig noAmount = DigestConfig.builder(DigestKind.REGULAR).digestValue("order-7").build();
        DigestGroupState state = DigestGroupState.empty();

        DigestGroup.Decision decision =
                DigestGroup.offer(state, event("e1", NOW), noAmount, NOW, ZoneOffset.UTC);

        assertEquals(DigestOutcome.CREATED, decision.outcome());
        assertTrue(decision.state().open(), "the mark is applied before the configuration is checked");
        assertThrows(DigestConfigurationException.class, decision::validateOrThrow);
    }

    private static DigestConfig backoff() {
        return DigestConfig.builder(DigestKind.BACKOFF).amount(5L).unit(DigestUnit.MINUTES)
                .backoff(true).backoffAmount(10L).backoffUnit(DigestUnit.MINUTES)
                .digestKey("orderId").digestValue("order-7").build();
    }
}
