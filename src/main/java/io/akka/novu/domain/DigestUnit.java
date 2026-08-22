package io.akka.novu.domain;

/**
 * The units a digest window can be expressed in, and what each is worth in
 * milliseconds — SPEC-001 §3 rule W1.
 *
 * <p>A month is a flat thirty days rather than a calendar month, which is what the
 * arithmetic being copied does (question-log row 2).
 */
public enum DigestUnit {
    SECONDS(1_000L),
    MINUTES(60_000L),
    HOURS(3_600_000L),
    DAYS(86_400_000L),
    WEEKS(604_800_000L),
    MONTHS(2_592_000_000L);

    private final long millis;

    DigestUnit(long millis) {
        this.millis = millis;
    }

    public long millis() {
        return millis;
    }

    public long times(long amount) {
        return millis * amount;
    }

    /** The wire name, lower case, as a digest configuration writes it. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Null where the name is not one of the six, which is how an override is rejected. */
    public static DigestUnit fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (DigestUnit unit : values()) {
            if (unit.wireName().equals(name.toLowerCase(java.util.Locale.ROOT))) {
                return unit;
            }
        }

        return null;
    }
}
