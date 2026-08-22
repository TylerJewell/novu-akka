package io.akka.novu.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything one digest group knows about itself — SPEC-001 §2.
 *
 * <p>The group holds its own history rather than querying for it. In the original, both the
 * backoff look-back and the reach are database queries over the whole job collection; here
 * the group is addressed by exactly the four fields those queries filter on, so the same
 * questions are answered by folding what this group has already seen.
 *
 * @param masterEventId the event holding the currently open digest, or null when none is open
 * @param openedAt when that event arrived — the anchor for the deadline and the reach
 * @param deadline when the open digest fires
 * @param openConfig the configuration the open digest was opened with, which is the one its
 *     reach is measured by; null when nothing is open
 * @param open whether a digest is currently collecting
 * @param events every event this group still remembers, oldest first
 */
public record DigestGroupState(
        String masterEventId,
        Instant openedAt,
        Instant deadline,
        DigestConfig openConfig,
        boolean open,
        List<Recorded> events) {

    /**
     * A group's remembered history is bounded. The original's equivalent is a database
     * collection with no ceiling; a durable entity replicated across regions has one, so the
     * oldest already-delivered records are dropped once the history passes this many. Only
     * delivered records are eligible: an undelivered one is still inside some digest's reach.
     */
    public static final int RETAINED = 500;

    /**
     * One event and what became of it.
     *
     * @param outcome null while an event has only been recorded and not yet decided on
     * @param delivered true once a digest carried this event out, so a later digest whose
     *     reach still covers it does not carry it again (SPEC-001 §3 rule R4)
     */
    public record Recorded(DigestEvent event, DigestOutcome outcome, boolean delivered) {
    }

    public static DigestGroupState empty() {
        return new DigestGroupState(null, null, null, null, false, List.of());
    }

    public DigestGroupState withEvents(List<Recorded> updated) {
        return new DigestGroupState(masterEventId, openedAt, deadline, openConfig, open, trim(updated));
    }

    public List<Recorded> mutableEvents() {
        return new ArrayList<>(events);
    }

    /** True where this event has already been decided on, so offering it again decides nothing. */
    public Recorded alreadySeen(String eventId) {
        for (Recorded recorded : events) {
            if (recorded.event().eventId().equals(eventId) && recorded.outcome() != null) {
                return recorded;
            }
        }

        return null;
    }

    static List<Recorded> trim(List<Recorded> records) {
        int toDrop = records.size() - RETAINED;
        if (toDrop <= 0) {
            return List.copyOf(records);
        }
        List<Recorded> kept = new ArrayList<>(records.size());
        for (Recorded recorded : records) {
            if (toDrop > 0 && recorded.delivered()) {
                toDrop--;
                continue;
            }
            kept.add(recorded);
        }

        return List.copyOf(kept);
    }
}
