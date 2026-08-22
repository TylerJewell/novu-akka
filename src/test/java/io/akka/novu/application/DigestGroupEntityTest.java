package io.akka.novu.application;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.novu.domain.DigestConfig;
import io.akka.novu.domain.DigestEvent;
import io.akka.novu.domain.DigestGroupEvent;
import io.akka.novu.domain.DigestGroupState;
import io.akka.novu.domain.DigestKind;
import io.akka.novu.domain.DigestOutcome;
import io.akka.novu.domain.DigestUnit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The group as a durable thing: what it writes, and what it comes back as — SPEC-001 §3
 * rules G1-G3, R4, and open decision OD-3.
 */
class DigestGroupEntityTest {

    private static final Instant NOON = Instant.parse("2026-03-10T12:00:00Z");

    private static final DigestConfig REGULAR = DigestConfig.builder(DigestKind.REGULAR)
            .amount(5L).unit(DigestUnit.MINUTES).digestValue("order-7").build();

    private static DigestGroupEntity.Offer offer(String eventId, Instant at) {
        return new DigestGroupEntity.Offer(
                new DigestEvent(eventId, "notif-" + eventId, at, Map.of("orderId", "order-7")),
                REGULAR, at, "UTC");
    }

    private static EventSourcedTestKit<DigestGroupState, DigestGroupEvent, DigestGroupEntity> kit() {
        return EventSourcedTestKit.of("group-1", DigestGroupEntity::new);
    }

    @Test
    void openingADigestWritesTheDeadlineAndTheConfigurationItWasOpenedWith() {
        var testKit = kit();

        var result = testKit.method(DigestGroupEntity::offer).invoke(offer("e1", NOON));

        assertThat(result.getReply().outcome()).isEqualTo(DigestOutcome.CREATED);
        DigestGroupEvent.DigestOpened written = result.getNextEventOfType(DigestGroupEvent.DigestOpened.class);
        assertThat(written.deadline()).isEqualTo(NOON.plusSeconds(300));
        assertThat(written.config().amount()).isEqualTo(5L);
        assertThat(written.configurationRejection()).isNull();
    }

    @Test
    void aMergeWritesAMergeAndLeavesTheDeadlineAlone() {
        var testKit = kit();
        testKit.method(DigestGroupEntity::offer).invoke(offer("e1", NOON));

        var result = testKit.method(DigestGroupEntity::offer).invoke(offer("e2", NOON.plusSeconds(30)));

        assertThat(result.getReply().outcome()).isEqualTo(DigestOutcome.MERGED);
        assertThat(result.getNextEventOfType(DigestGroupEvent.EventMerged.class).event().eventId())
                .isEqualTo("e2");
        assertThat(testKit.getState().deadline()).isEqualTo(NOON.plusSeconds(300));
    }

    @Test
    void aRepeatedOfferWritesNothing() {
        var testKit = kit();
        testKit.method(DigestGroupEntity::offer).invoke(offer("e1", NOON));

        var repeat = testKit.method(DigestGroupEntity::offer).invoke(offer("e1", NOON));

        assertThat(repeat.didPersistEvents()).isFalse();
        assertThat(repeat.getReply().outcome()).isEqualTo(DigestOutcome.CREATED);
        assertThat(testKit.getState().events()).hasSize(1);
    }

    @Test
    void deliveringTwiceForOneDeadlineIsHarmless() {
        var testKit = kit();
        testKit.method(DigestGroupEntity::offer).invoke(offer("e1", NOON));

        var first = testKit.method(DigestGroupEntity::deliver).invoke();
        var second = testKit.method(DigestGroupEntity::deliver).invoke();

        assertThat(first.getReply().wasOpen()).isTrue();
        assertThat(first.getReply().events()).hasSize(1);
        assertThat(second.getReply().wasOpen()).isFalse();
        assertThat(second.didPersistEvents()).isFalse();
    }

    @Test
    void anUnknownZoneIsRefusedRatherThanCrashing() {
        var testKit = kit();

        var result = testKit.method(DigestGroupEntity::offer).invoke(
                new DigestGroupEntity.Offer(
                        new DigestEvent("e1", "notif-e1", NOON, Map.of()),
                        DigestConfig.builder(DigestKind.TIMED).amount(1L).unit(DigestUnit.DAYS)
                                .timed(io.akka.novu.domain.TimedConfig.atTime("09:00")).build(),
                        NOON, "Mars/Olympus_Mons"));

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("Unknown time zone");
    }

    @Test
    void aGroupRebuiltFromItsHistoryIsTheGroupItWas() {
        // The point of OD-3: a digest waiting on a deadline survives being reconstructed.
        var testKit = kit();
        testKit.method(DigestGroupEntity::offer).invoke(offer("e1", NOON));
        testKit.method(DigestGroupEntity::offer).invoke(offer("e2", NOON.plusSeconds(30)));
        DigestGroupState before = testKit.getState();

        var rebuilt = EventSourcedTestKit.<DigestGroupState, DigestGroupEvent, DigestGroupEntity>of(
                "group-1", DigestGroupEntity::new);
        for (var event : testKit.getAllEvents()) {
            rebuilt.method(DigestGroupEntity::offer).invoke(offer(
                    event instanceof DigestGroupEvent.DigestOpened opened
                            ? opened.event().eventId()
                            : ((DigestGroupEvent.EventMerged) event).event().eventId(),
                    event instanceof DigestGroupEvent.DigestOpened opened
                            ? opened.event().createdAt()
                            : ((DigestGroupEvent.EventMerged) event).event().createdAt()));
        }

        assertThat(rebuilt.getState().masterEventId()).isEqualTo(before.masterEventId());
        assertThat(rebuilt.getState().deadline()).isEqualTo(before.deadline());
        assertThat(rebuilt.getState().events()).hasSameSizeAs(before.events());
    }

    @Test
    void theRememberedHistoryIsBounded() {
        // A durable entity has a replication ceiling the original's collection does not, so
        // the group forgets its oldest already-delivered events rather than growing forever.
        var testKit = kit();
        for (int i = 0; i < DigestGroupState.RETAINED + 50; i++) {
            testKit.method(DigestGroupEntity::offer).invoke(offer("e" + i, NOON.plusSeconds(i)));
            testKit.method(DigestGroupEntity::deliver).invoke();
        }

        assertThat(testKit.getState().events().size()).isLessThanOrEqualTo(DigestGroupState.RETAINED);
    }
}
