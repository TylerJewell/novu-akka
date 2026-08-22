package io.akka.novu.domain;

/**
 * What a step's timing configuration is. {@link #UNTYPED} is a configuration that
 * names no kind at all: it is a real state a caller can be in, and it answers
 * differently from an absent configuration (SPEC-001 §3 rules V2, W5, W6).
 */
public enum DigestKind {
    REGULAR,
    BACKOFF,
    TIMED,
    SCHEDULED,
    DYNAMIC,
    UNTYPED
}
