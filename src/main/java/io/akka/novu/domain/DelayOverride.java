package io.akka.novu.domain;

/**
 * A trigger-time replacement for a step's own amount and unit — SPEC-001 §3 rules W3, W4.
 *
 * <p>{@code amount} is held as an untyped value on purpose. An override whose amount
 * arrives as text is ignored where a step's own amount in the same shape is acted on
 * (question-log row 3), and that asymmetry is only expressible if the override keeps what
 * it was given rather than what it could be read as.
 */
public record DelayOverride(Object amount, String unit) {

    /** True only when both halves are usable; a half-valid override applies neither half. */
    public boolean isUsable() {
        return amount instanceof Number && DigestUnit.fromWireName(unit) != null;
    }

    public long millis() {
        return DigestUnit.fromWireName(unit).times(((Number) amount).longValue());
    }
}
