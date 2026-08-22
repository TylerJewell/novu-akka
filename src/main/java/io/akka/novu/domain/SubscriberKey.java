package io.akka.novu.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Where one subscriber's preferences live — SPEC-001 §3 rules C5, C6.
 *
 * <p>Encoded rather than joined with a separator for the same two reasons
 * {@link DigestGroupKey} is: a subscriber identifier containing the separator would
 * otherwise read as two fields, and the runtime rejects several punctuation characters in
 * an entity identifier outright (question-log row 36).
 */
public record SubscriberKey(String environmentId, String subscriberId) {

    public String id() {
        return encode(environmentId) + "." + encode(subscriberId);
    }

    private static String encode(String part) {
        return part == null
                ? "~"
                : Base64.getUrlEncoder().withoutPadding().encodeToString(part.getBytes(StandardCharsets.UTF_8));
    }
}
