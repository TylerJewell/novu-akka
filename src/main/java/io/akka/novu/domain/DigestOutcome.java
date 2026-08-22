package io.akka.novu.domain;

/** What offering an event to a digest group did — SPEC-001 §3 rules G1, G2, G5. */
public enum DigestOutcome {
    /** This event opened a digest and is holding it. */
    CREATED,
    /** This event joined a digest another event is holding. */
    MERGED,
    /** This event is not digested at all and goes out on its own. */
    SKIPPED
}
