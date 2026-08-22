package io.akka.novu.domain;

/** A digest configuration that cannot be acted on — SPEC-001 §3 rules V1-V7, W5, W7, W8. */
public class DigestConfigurationException extends RuntimeException {

    public DigestConfigurationException(String message) {
        super(message);
    }
}
