package io.akka.novu.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.novu.domain.DigestConfig;
import io.akka.novu.domain.DigestEvent;
import io.akka.novu.domain.DigestGroup;
import io.akka.novu.domain.DigestGroupEvent;
import io.akka.novu.domain.DigestGroupState;
import io.akka.novu.domain.DigestOutcome;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * One digest group, addressed by the four fields that decide grouping — SPEC-001 §3
 * rules G1-G3, R1-R4.
 *
 * <p>In the original, "is there already a digest for this value" and "which events belong
 * to this digest" are queries across the whole job collection, filtered on environment,
 * workflow, subscriber and digest value. Here those four fields are the entity's own
 * address, so both questions are answered from the group's own history and neither can see
 * another group's events.
 *
 * <p>The group also keeps its deadline, which is what lets a digest survive a restart: the
 * original's window lives in a delayed queue entry, and nothing inside the slice says what
 * becomes of it if the process holding it dies (SPEC-001 open decision OD-3).
 */
@Component(id = "digest-group")
public class DigestGroupEntity extends EventSourcedEntity<DigestGroupState, DigestGroupEvent> {

    /**
     * @param now the moment the offer is being decided, which the backoff look-back measures
     *     from; supplied by the caller so a benchmark and a test can pin it
     * @param zone the zone a timed schedule's time of day is read in
     */
    public record Offer(DigestEvent event, DigestConfig config, Instant now, String zone) {
    }

    /**
     * @param mergedInto the event holding the digest this one joined, or null
     * @param deadline when the open digest fires, or null where nothing is open
     * @param configurationRejection why the configuration was refused, or null; the digest
     *     is opened either way (rule G7)
     */
    public record Offered(DigestOutcome outcome, String groupId, String mergedInto,
                          Instant deadline, String configurationRejection) {
    }

    public record Delivered(List<DigestEvent> events, boolean wasOpen) {
    }

    private final String groupId;

    public DigestGroupEntity(akka.javasdk.eventsourcedentity.EventSourcedEntityContext context) {
        this.groupId = context.entityId();
    }

    @Override
    public DigestGroupState emptyState() {
        return DigestGroupState.empty();
    }

    public ReadOnlyEffect<DigestGroupState> get() {
        return effects().reply(currentState());
    }

    public Effect<Offered> offer(Offer offer) {
        ZoneId zone;
        try {
            zone = offer.zone() == null ? ZoneId.of("UTC") : ZoneId.of(offer.zone());
        } catch (RuntimeException e) {
            // A zone name the platform does not know is a caller mistake, not a fault. Raised
            // as a CommandException so it crosses the node boundary as itself rather than as
            // an opaque 500.
            return effects().error("Unknown time zone");
        }
        Instant now = offer.now() == null ? offer.event().createdAt() : offer.now();

        // An offer may be redelivered. Deciding the same event twice would put it in the
        // digest twice, so a decision already on record is the answer and nothing is written.
        DigestGroupState.Recorded seen = currentState().alreadySeen(offer.event().eventId());
        if (seen != null) {
            return effects().reply(new Offered(seen.outcome(), groupId,
                    seen.outcome() == DigestOutcome.MERGED ? currentState().masterEventId() : null,
                    currentState().deadline(), null));
        }

        DigestGroup.Decision decision =
                DigestGroup.offer(currentState(), offer.event(), offer.config(), now, zone);

        DigestGroupEvent persisted = switch (decision.outcome()) {
            case CREATED -> new DigestGroupEvent.DigestOpened(
                    offer.event(),
                    decision.state().deadline(),
                    offer.config(),
                    decision.configurationError() == null ? null : decision.configurationError().getMessage());
            case MERGED -> new DigestGroupEvent.EventMerged(offer.event());
            case SKIPPED -> new DigestGroupEvent.EventSkipped(offer.event());
        };

        return effects().persist(persisted).thenReply(state -> new Offered(
                decision.outcome(),
                groupId,
                decision.mergedInto(),
                state.deadline(),
                decision.configurationError() == null ? null : decision.configurationError().getMessage()));
    }

    /** An event that arrived with no digest step deciding on it — SPEC-001 §3 rule R1. */
    public Effect<DigestGroupState> record(DigestEvent event) {
        return effects().persist(new DigestGroupEvent.EventRecorded(event)).thenReply(state -> state);
    }

    /**
     * Fire the open digest. Delivering a group with nothing open answers an empty digest
     * rather than failing: the timer that calls this is at-least-once, so a second arrival
     * for one deadline must be harmless.
     */
    public Effect<Delivered> deliver() {
        if (!currentState().open()) {
            return effects().reply(new Delivered(List.of(), false));
        }
        DigestGroup.Delivery delivery = DigestGroup.deliver(currentState());

        return effects()
                .persist(new DigestGroupEvent.DigestDelivered(
                        delivery.delivered().stream().map(DigestEvent::eventId).toList()))
                .thenReply(state -> new Delivered(delivery.delivered(), true));
    }

    @Override
    public DigestGroupState applyEvent(DigestGroupEvent event) {
        return switch (event) {
            case DigestGroupEvent.EventRecorded e -> DigestGroup.record(currentState(), e.event());
            case DigestGroupEvent.DigestOpened e ->
                    DigestGroup.open(currentState(), e.event(), e.deadline(), e.config());
            case DigestGroupEvent.EventMerged e ->
                    DigestGroup.add(currentState(), e.event(), DigestOutcome.MERGED);
            case DigestGroupEvent.EventSkipped e ->
                    DigestGroup.add(currentState(), e.event(), DigestOutcome.SKIPPED);
            case DigestGroupEvent.DigestDelivered e -> DigestGroup.markDelivered(currentState(), e.eventIds());
        };
    }
}
