package io.akka.novu.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * How long a step holds events before it fires — SPEC-001 §3 rules W1-W9.
 *
 * <p>The answer is in milliseconds so that a caller can compare it against the original's
 * without a unit conversion sitting between the two numbers.
 */
public final class DigestWindow {

    private DigestWindow() {
    }

    /**
     * @param config the step's own configuration, or null
     * @param payload the trigger payload, read only by the scheduled and dynamic kinds
     * @param override a trigger-time replacement for the amount and unit, or null
     * @param now the instant the window is measured from
     * @param zone the zone a timed schedule's time of day is read in
     */
    public static long of(DigestConfig config, Map<String, Object> payload, DelayOverride override,
                          Instant now, ZoneId zone) {
        if (config == null) {
            throw new DigestConfigurationException("Step metadata not found");
        }

        return switch (config.kind()) {
            case SCHEDULED -> scheduled(config, payload, now);
            case DYNAMIC -> dynamic(config, payload, now);
            case REGULAR, BACKOFF -> fixed(config, override);
            case TIMED -> TimedDigestSchedule.millisUntilNextOccurrence(config, now, zone);
            case UNTYPED -> config.amount() != null && config.amount() != 0 && config.unit() != null
                    ? fixed(config, override)
                    : 0L;
        };
    }

    private static long fixed(DigestConfig config, DelayOverride override) {
        if (override != null && override.isUsable()) {
            return override.millis();
        }
        if (config.amount() == null || config.unit() == null) {
            return 0L;
        }

        return config.unit().times(config.amount());
    }

    private static long scheduled(DigestConfig config, Map<String, Object> payload, Instant now) {
        if (config.delayPath() == null || config.delayPath().isBlank()) {
            throw new DigestConfigurationException("Delay path not found");
        }
        Instant at = instantAt(payload, config.delayPath());
        long delay = Duration.between(now, at).toMillis();
        if (delay < 0) {
            throw new DigestConfigurationException("Delay date at path must be a future date");
        }

        return delay;
    }

    /**
     * A dynamic delay reads either a timestamp or a {@code {amount, unit}} pair out of the
     * payload. A timestamp already past is rejected the way a scheduled one is; a duration
     * is taken as stated.
     */
    private static long dynamic(DigestConfig config, Map<String, Object> payload, Instant now) {
        if (config.dynamicKey() == null || config.dynamicKey().isBlank()) {
            throw new DigestConfigurationException("Dynamic delay key not found");
        }
        Object value = Payloads.at(payload, config.dynamicKey());
        if (value == null) {
            throw new DigestConfigurationException(
                    "Dynamic delay key '" + config.dynamicKey() + "' not found in payload");
        }
        if (value instanceof Map<?, ?> duration) {
            Object amount = duration.get("amount");
            Object unit = duration.get("unit");
            if (!(amount instanceof Number number) || number.longValue() < 0) {
                throw new DigestConfigurationException("Invalid amount in dynamic delay");
            }
            DigestUnit resolved = DigestUnit.fromWireName(String.valueOf(unit));
            if (resolved == null) {
                throw new DigestConfigurationException("Invalid time unit in dynamic delay");
            }

            return resolved.times(number.longValue());
        }
        long delay = Duration.between(now, parseInstant(String.valueOf(value))).toMillis();
        if (delay < 0) {
            // The key is named and the value is not: the value came out of a trigger payload
            // and a rejection is a place user data leaves the system through.
            throw new DigestConfigurationException(
                    "Dynamic delay timestamp at '" + config.dynamicKey() + "' must be a future date");
        }

        return delay;
    }

    private static Instant instantAt(Map<String, Object> payload, String path) {
        Object raw = Payloads.at(payload, path);
        if (raw == null) {
            throw new DigestConfigurationException("Delay date at path must be a future date");
        }

        return parseInstant(String.valueOf(raw));
    }

    private static Instant parseInstant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new DigestConfigurationException(
                    "Delay value is not a valid format."
                            + " Expected ISO-8601 timestamp or duration object { amount, unit }");
        }
    }
}
