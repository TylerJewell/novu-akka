package io.akka.novu.domain;

import akka.javasdk.annotations.TypeName;

import java.time.Instant;
import java.util.List;

/** What happened to a digest group — the durable record behind {@link DigestGroupState}. */
public sealed interface DigestGroupEvent {

    /** An event arrived against this group without any digest step deciding on it yet. */
    @TypeName("event-recorded")
    record EventRecorded(DigestEvent event) implements DigestGroupEvent {
    }

    /**
     * @param deadline when the digest this event opened will fire
     * @param config the configuration the digest was opened with, kept because its amount and
     *     unit are what the reach is measured by when the digest finally fires
     * @param configurationRejection the message the configuration earned, or null; held on
     *     the event because the opening is durable whether or not the configuration stood
     *     up to checking (SPEC-001 §3 rule G7)
     */
    @TypeName("digest-opened")
    record DigestOpened(DigestEvent event, Instant deadline, DigestConfig config,
                        String configurationRejection) implements DigestGroupEvent {
    }

    @TypeName("event-merged")
    record EventMerged(DigestEvent event) implements DigestGroupEvent {
    }

    @TypeName("event-skipped")
    record EventSkipped(DigestEvent event) implements DigestGroupEvent {
    }

    @TypeName("digest-delivered")
    record DigestDelivered(List<String> eventIds) implements DigestGroupEvent {
    }
}
