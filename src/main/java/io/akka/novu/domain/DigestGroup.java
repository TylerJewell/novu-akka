package io.akka.novu.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The decision at the heart of the digest engine — SPEC-001 §3 rules G1-G7, R1-R4.
 *
 * <p>Given a group's history and one arriving event: does the event open a digest, join the
 * one already open, or bypass digesting altogether? And when the open digest fires, which
 * of the events seen belong to it?
 */
public final class DigestGroup {

    private DigestGroup() {
    }

    /**
     * The answer to one offered event.
     *
     * @param mergedInto the event holding the digest this one joined, or null
     * @param configurationError the rejection the configuration earns, held rather than
     *     thrown so that the state change preceding it is still visible (rule G7)
     */
    public record Decision(
            DigestOutcome outcome,
            DigestGroupState state,
            String mergedInto,
            DigestConfigurationException configurationError) {

        public void validateOrThrow() {
            if (configurationError != null) {
                throw configurationError;
            }
        }
    }

    /** What a firing digest carried, and the group as it stands afterwards. */
    public record Delivery(List<DigestEvent> delivered, DigestGroupState state) {
    }

    /**
     * Record an event without deciding anything about it. Used where an event arrived
     * against this group before any digest step ran on it — it is inside the reach of a
     * digest opened later (rule R1) without ever having been offered one.
     */
    public static DigestGroupState record(DigestGroupState state, DigestEvent event) {
        List<DigestGroupState.Recorded> events = state.mutableEvents();
        events.add(new DigestGroupState.Recorded(event, null, false));

        return state.withEvents(events);
    }

    public static Decision offer(DigestGroupState state, DigestEvent event, DigestConfig config,
                                 Instant now, ZoneId zone) {
        // An offer may be redelivered. Deciding the same event twice would put it in the
        // digest twice, so a decision already on record is the answer.
        DigestGroupState.Recorded seen = state.alreadySeen(event.eventId());
        if (seen != null) {
            return new Decision(seen.outcome(), state,
                    seen.outcome() == DigestOutcome.MERGED ? state.masterEventId() : null, null);
        }
        if (config != null && config.isBackoff()) {
            Optional<Instant> earliest = earliestInLookBack(state, event, config, now);
            if (earliest.isEmpty() || event.createdAt().isBefore(earliest.get())) {
                return skip(state, event);
            }
        }

        return state.open() ? merge(state, event) : create(state, event, config, now, zone);
    }

    /**
     * The other events of this group that arrived inside {@code backoffAmount ×
     * backoffUnit} before now, excluding this event's own notification — SPEC-001 §3 rule
     * G5. The look-back is measured from now rather than from the arriving event, which is
     * what makes a burst's first event skip and its second create.
     */
    private static Optional<Instant> earliestInLookBack(DigestGroupState state, DigestEvent event,
                                                        DigestConfig config, Instant now) {
        long window = config.backoffUnit() == null || config.backoffAmount() == null
                ? 0L
                : config.backoffUnit().times(config.backoffAmount());
        Instant cutoff = now.minusMillis(window);

        return state.events().stream()
                .map(DigestGroupState.Recorded::event)
                .filter(other -> !other.notificationId().equals(event.notificationId()))
                .filter(other -> !other.createdAt().isBefore(cutoff))
                .map(DigestEvent::createdAt)
                .min(Comparator.naturalOrder());
    }

    private static Decision skip(DigestGroupState state, DigestEvent event) {
        return new Decision(DigestOutcome.SKIPPED, add(state, event, DigestOutcome.SKIPPED), null, null);
    }

    private static Decision merge(DigestGroupState state, DigestEvent event) {
        return new Decision(DigestOutcome.MERGED, add(state, event, DigestOutcome.MERGED),
                state.masterEventId(), null);
    }

    /**
     * Opening a digest marks the event master first and checks the configuration second,
     * which is the order the original takes and is observable: a caller reading the group
     * in between sees an open digest with nothing behind it (rule G7).
     */
    private static Decision create(DigestGroupState state, DigestEvent event, DigestConfig config,
                                   Instant now, ZoneId zone) {
        DigestConfigurationException error = null;
        Instant deadline = event.createdAt();
        try {
            if (config != null && config.isRegularLike()
                    && (config.amount() == null || config.amount() == 0L || config.unit() == null)) {
                throw new DigestConfigurationException(
                        "Somehow " + event.eventId() + " had wrong digest settings and escaped validation");
            }
            deadline = event.createdAt().plusMillis(
                    DigestWindow.of(config, event.payload(), null, now, zone));
        } catch (DigestConfigurationException e) {
            error = e;
        }

        return new Decision(DigestOutcome.CREATED, open(state, event, deadline, config), null, error);
    }

    /**
     * How far back the open digest reaches — SPEC-001 §3 rules R1-R3. Anchored on the
     * opening event's arrival, not on the moment of firing, so the answer does not move
     * while the digest waits.
     */
    public static Instant reachFrom(DigestGroupState state) {
        DigestConfig config = state.openConfig();
        Instant anchor = state.openedAt() == null ? Instant.EPOCH : state.openedAt();
        if (config == null || config.amount() == null || config.unit() == null) {
            return anchor;
        }

        return anchor.minusMillis(config.unit().times(config.amount()));
    }

    /** The events an open digest would carry, in arrival order — SPEC-001 §3 rules R1-R4. */
    public static List<DigestEvent> collect(DigestGroupState state) {
        Instant from = reachFrom(state);
        List<DigestEvent> collected = new ArrayList<>();
        for (DigestGroupState.Recorded recorded : state.events()) {
            if (recorded.delivered() || recorded.outcome() == DigestOutcome.SKIPPED) {
                continue;
            }
            if (!recorded.event().createdAt().isBefore(from)) {
                collected.add(recorded.event());
            }
        }
        collected.sort(Comparator.comparing(DigestEvent::createdAt).thenComparing(DigestEvent::eventId));

        return collected;
    }

    /**
     * Fire the open digest. What it carried is marked delivered, so a later digest whose
     * reach still covers those events does not carry them a second time (rule R4).
     */
    public static Delivery deliver(DigestGroupState state) {
        List<DigestEvent> delivered = collect(state);

        return new Delivery(delivered,
                markDelivered(state, delivered.stream().map(DigestEvent::eventId).toList()));
    }

    /**
     * Close the digest and mark what it carried. Shared with the entity's event replay, so a
     * group rebuilt from its history reaches the same state as one that was never restarted.
     */
    public static DigestGroupState markDelivered(DigestGroupState state, List<String> eventIds) {
        List<DigestGroupState.Recorded> events = new ArrayList<>();
        for (DigestGroupState.Recorded recorded : state.events()) {
            events.add(eventIds.contains(recorded.event().eventId())
                    ? new DigestGroupState.Recorded(recorded.event(), recorded.outcome(), true)
                    : recorded);
        }

        return new DigestGroupState(null, null, null, null, false, DigestGroupState.trim(events));
    }

    /** Open a digest on an event, with the deadline already computed. Shared with event replay. */
    public static DigestGroupState open(DigestGroupState state, DigestEvent event, Instant deadline,
                                        DigestConfig config) {
        List<DigestGroupState.Recorded> events = state.mutableEvents();
        events.add(new DigestGroupState.Recorded(event, DigestOutcome.CREATED, false));

        return new DigestGroupState(event.eventId(), event.createdAt(), deadline, config, true,
                DigestGroupState.trim(events));
    }

    /** Add an event with a settled outcome, leaving the open digest where it is. Shared with replay. */
    public static DigestGroupState add(DigestGroupState state, DigestEvent event, DigestOutcome outcome) {
        List<DigestGroupState.Recorded> events = state.mutableEvents();
        events.add(new DigestGroupState.Recorded(event, outcome, outcome == DigestOutcome.SKIPPED));

        return state.withEvents(events);
    }
}
