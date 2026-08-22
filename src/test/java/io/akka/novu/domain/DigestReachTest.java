package io.akka.novu.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SPEC-001 §3 rules R1-R4 — how far back a firing digest reaches. */
class DigestReachTest {

    private static final Instant OPENED = Instant.parse("2026-03-10T12:00:00Z");

    private static DigestEvent event(String id, Instant createdAt) {
        return new DigestEvent(id, "notif-" + id, createdAt, Map.of("orderId", "order-7"));
    }

    private static DigestConfig regular(Long amount, DigestUnit unit) {
        DigestConfig.Builder builder = DigestConfig.builder(DigestKind.REGULAR).digestValue("order-7");
        if (amount != null) {
            builder.amount(amount);
        }
        if (unit != null) {
            builder.unit(unit);
        }

        return builder.build();
    }

    @Test
    void reachIsAnchoredOnTheOpeningEventNotOnTheFiring() {
        // R1. The bound is openedAt minus the window, so an event that arrived four minutes
        // before the digest opened is inside a five-minute reach and one six minutes before
        // it is outside -- and neither answer moves when the digest fires later.
        DigestConfig config = regular(5L, DigestUnit.MINUTES);
        DigestGroupState state = DigestGroupState.empty();
        state = DigestGroup.record(state, event("early", OPENED.minusSeconds(6 * 60)));
        state = DigestGroup.record(state, event("late", OPENED.minusSeconds(4 * 60)));
        state = DigestGroup.offer(state, event("master", OPENED), config, OPENED, ZoneOffset.UTC).state();

        assertEquals(OPENED.minusSeconds(5 * 60), DigestGroup.reachFrom(state));
        assertEquals(List.of("late", "master"), ids(DigestGroup.collect(state)));
    }

    @Test
    void anAmountWrittenAsAStringIsStillSubtracted() {
        // R2.
        DigestConfig config = DigestConfig.builder(DigestKind.REGULAR)
                .amount(DigestConfig.parseAmount("30")).unit(DigestUnit.MINUTES).digestValue("order-7").build();
        DigestGroupState state = DigestGroup
                .offer(DigestGroupState.empty(), event("master", OPENED), config, OPENED, ZoneOffset.UTC).state();

        assertEquals(OPENED.minusSeconds(30 * 60), DigestGroup.reachFrom(state));
    }

    @Test
    void aConfigurationWithNoUnitReachesNoFurtherThanTheOpeningEvent() {
        // R3 -- a zero-width reach, not an unbounded one.
        DigestConfig config = regular(null, null);
        DigestGroupState state = DigestGroupState.empty();
        state = DigestGroup.record(state, event("earlier", OPENED.minusSeconds(1)));
        state = DigestGroup.offer(state, event("master", OPENED), config, OPENED, ZoneOffset.UTC).state();

        assertEquals(OPENED, DigestGroup.reachFrom(state));
        assertEquals(List.of("master"), ids(DigestGroup.collect(state)));
    }

    @Test
    void deliveredEventIsNotCollectedTwice() {
        // R4. Two digests over one event stream: what the first delivered is gone from the
        // second, even though the second's reach still covers the moment it arrived.
        DigestConfig config = regular(30L, DigestUnit.MINUTES);
        DigestGroupState state = DigestGroupState.empty();
        state = DigestGroup.offer(state, event("e1", OPENED), config, OPENED, ZoneOffset.UTC).state();
        state = DigestGroup.offer(state, event("e2", OPENED.plusSeconds(60)), config,
                OPENED.plusSeconds(60), ZoneOffset.UTC).state();

        DigestGroup.Delivery first = DigestGroup.deliver(state);
        assertEquals(List.of("e1", "e2"), ids(first.delivered()));

        Instant later = OPENED.plusSeconds(300);
        DigestGroupState reopened = DigestGroup.offer(first.state(), event("e3", later), config, later,
                ZoneOffset.UTC).state();
        DigestGroup.Delivery second = DigestGroup.deliver(reopened);

        assertEquals(List.of("e3"), ids(second.delivered()));
    }

    @Test
    void collectionIsInArrivalOrder() {
        // The digest's contents are what a template renders, so their order is part of the
        // answer rather than an incidental of how they were stored.
        DigestConfig config = regular(30L, DigestUnit.MINUTES);
        DigestGroupState state = DigestGroupState.empty();
        state = DigestGroup.offer(state, event("e1", OPENED), config, OPENED, ZoneOffset.UTC).state();
        state = DigestGroup.offer(state, event("e3", OPENED.plusSeconds(120)), config,
                OPENED.plusSeconds(120), ZoneOffset.UTC).state();
        state = DigestGroup.offer(state, event("e2", OPENED.plusSeconds(60)), config,
                OPENED.plusSeconds(180), ZoneOffset.UTC).state();

        assertEquals(List.of("e1", "e2", "e3"), ids(DigestGroup.collect(state)));
    }

    @Test
    void aSkippedEventIsNotPartOfAnyDigest() {
        // A backoff skip sends immediately and undigested, so the event must not turn up in
        // the digest that a later event opens.
        DigestConfig config = DigestConfig.builder(DigestKind.BACKOFF).amount(30L).unit(DigestUnit.MINUTES)
                .backoff(true).backoffAmount(10L).backoffUnit(DigestUnit.MINUTES).digestValue("order-7").build();
        DigestGroupState state = DigestGroupState.empty();
        state = DigestGroup.offer(state, event("skipped", OPENED), config, OPENED, ZoneOffset.UTC).state();
        state = DigestGroup.offer(state, event("master", OPENED.plusSeconds(60)), config,
                OPENED.plusSeconds(60), ZoneOffset.UTC).state();

        assertEquals(List.of("master"), ids(DigestGroup.collect(state)));
    }

    private static List<String> ids(List<DigestEvent> events) {
        return events.stream().map(DigestEvent::eventId).toList();
    }
}
