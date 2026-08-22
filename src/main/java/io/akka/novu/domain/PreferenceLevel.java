package io.akka.novu.domain;

/**
 * The four levels a workflow preference merge folds, in the order they are applied —
 * SPEC-001 §3 rule C5. Declaration order is the precedence.
 */
public enum PreferenceLevel {
    WORKFLOW_RESOURCE,
    WORKFLOW_USER,
    SUBSCRIBER_GLOBAL,
    SUBSCRIBER_WORKFLOW
}
