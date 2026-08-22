package io.akka.novu.domain;

/** Which shape a monthly timed digest takes — SPEC-001 §3 rule V7. */
public enum MonthlyType {
    /** Named days of the month. */
    EACH,
    /** An ordinal occurrence within the month, such as the second Tuesday. */
    ON
}
