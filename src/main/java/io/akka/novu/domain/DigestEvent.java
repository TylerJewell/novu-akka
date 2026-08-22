package io.akka.novu.domain;

import java.time.Instant;
import java.util.Map;

/**
 * One notification event offered to a digest — SPEC-001 §2.
 *
 * @param eventId what this event is called
 * @param notificationId the notification it belongs to; the backoff look-back ignores
 *     events from the same notification (SPEC-001 §3 rule G5)
 * @param createdAt when it arrived, which anchors both the window and the reach
 * @param payload the trigger payload, kept because a digest renders it
 */
public record DigestEvent(String eventId, String notificationId, Instant createdAt, Map<String, Object> payload) {
}
