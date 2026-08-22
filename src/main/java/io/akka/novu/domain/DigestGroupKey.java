package io.akka.novu.domain;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * What makes two events part of the same digest — SPEC-001 §3 rule G3.
 *
 * <p>Four fields and no others: environment, workflow, subscriber and digest value. The
 * key is also the identity of the durable group, so a rule that is a database query in the
 * original is the address of an entity here.
 *
 * @param digestValue null where the workflow groups by nothing, which is a value of its own
 *     and is not the same group as an empty string
 */
public record DigestGroupKey(String environmentId, String workflowId, String subscriberId, String digestValue) {

    /**
     * A single opaque identifier. The four parts are encoded rather than joined so that a
     * value containing the separator cannot be read as two fields.
     */
    public String id() {
        StringBuilder joined = new StringBuilder();
        for (String part : new String[] {environmentId, workflowId, subscriberId, digestValue}) {
            joined.append(part == null ? "~" : encode(part)).append('.');
        }

        return joined.toString();
    }

    private static String encode(String part) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(part.getBytes(StandardCharsets.UTF_8));
    }
}
