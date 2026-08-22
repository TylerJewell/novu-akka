package io.akka.novu.domain;

/** The step kinds a workflow is built from — SPEC-001 §3 rule V1. */
public enum StepType {
    IN_APP,
    EMAIL,
    SMS,
    CHAT,
    PUSH,
    TOOL,
    DIGEST,
    TRIGGER,
    DELAY,
    THROTTLE,
    CUSTOM,
    HTTP_REQUEST
}
