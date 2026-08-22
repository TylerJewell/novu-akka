package io.akka.novu.domain;

/**
 * The three sources that can state a channel preference, in the order they are applied —
 * SPEC-001 §3 rule C2. Declaration order is the precedence: the last source to name a
 * channel is the one that stands.
 */
public enum PreferenceSource {
    WORKFLOW_RESOURCE,
    WORKFLOW_OVERRIDE,
    SUBSCRIBER
}
