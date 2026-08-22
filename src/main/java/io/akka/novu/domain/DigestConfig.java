package io.akka.novu.domain;

/**
 * A digest step's timing configuration — SPEC-001 §2.
 *
 * <p>Every field is optional at construction, because the question of which fields a
 * configuration must carry is answered by {@link DigestConfigValidation} against the
 * kind, not by the type. A configuration that is missing what its kind needs is a real
 * thing a caller can send, and the answers to W6 and V3 differ.
 *
 * @param kind which of the timing shapes this is
 * @param amount how many {@code unit}s the window lasts; null where none was stated
 * @param unit the unit the amount is in
 * @param timed the schedule, for a timed digest
 * @param backoff marks the configuration as backoff whatever the kind says (rule G4)
 * @param backoffAmount how far back the backoff look-back reaches
 * @param backoffUnit the unit that look-back is in
 * @param digestKey the payload path whose value groups events
 * @param digestValue the value at that path, carried on the configuration so grouping
 *     does not depend on the payload still being around
 * @param delayPath the payload path holding a date, for a scheduled delay
 * @param dynamicKey the payload path holding a duration or a date, for a dynamic delay
 */
public record DigestConfig(
        DigestKind kind,
        Long amount,
        DigestUnit unit,
        TimedConfig timed,
        Boolean backoff,
        Long backoffAmount,
        DigestUnit backoffUnit,
        String digestKey,
        String digestValue,
        String delayPath,
        String dynamicKey) {

    /**
     * An amount that arrived as text. A digest configuration may carry its amount as a
     * string, and both the window and the reach act on it as a number (question-log rows
     * 3 and 20). Unparseable text is null, which reads the same as an absent amount.
     */
    public static Long parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True when any of the three markers puts this configuration on the backoff path. */
    public boolean isBackoff() {
        return kind == DigestKind.BACKOFF || Boolean.TRUE.equals(backoff);
    }

    public boolean isRegularLike() {
        return kind == DigestKind.REGULAR || kind == DigestKind.BACKOFF;
    }

    public static Builder builder(DigestKind kind) {
        return new Builder(kind);
    }

    public static final class Builder {
        private final DigestKind kind;
        private Long amount;
        private DigestUnit unit;
        private TimedConfig timed;
        private Boolean backoff;
        private Long backoffAmount;
        private DigestUnit backoffUnit;
        private String digestKey;
        private String digestValue;
        private String delayPath;
        private String dynamicKey;

        private Builder(DigestKind kind) {
            this.kind = kind;
        }

        public Builder amount(Long amount) {
            this.amount = amount;
            return this;
        }

        public Builder unit(DigestUnit unit) {
            this.unit = unit;
            return this;
        }

        public Builder timed(TimedConfig timed) {
            this.timed = timed;
            return this;
        }

        public Builder backoff(Boolean backoff) {
            this.backoff = backoff;
            return this;
        }

        public Builder backoffAmount(Long backoffAmount) {
            this.backoffAmount = backoffAmount;
            return this;
        }

        public Builder backoffUnit(DigestUnit backoffUnit) {
            this.backoffUnit = backoffUnit;
            return this;
        }

        public Builder digestKey(String digestKey) {
            this.digestKey = digestKey;
            return this;
        }

        public Builder digestValue(String digestValue) {
            this.digestValue = digestValue;
            return this;
        }

        public Builder delayPath(String delayPath) {
            this.delayPath = delayPath;
            return this;
        }

        public Builder dynamicKey(String dynamicKey) {
            this.dynamicKey = dynamicKey;
            return this;
        }

        public DigestConfig build() {
            return new DigestConfig(kind, amount, unit, timed, backoff, backoffAmount, backoffUnit,
                    digestKey, digestValue, delayPath, dynamicKey);
        }
    }
}
