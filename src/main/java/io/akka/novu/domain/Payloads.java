package io.akka.novu.domain;

import java.util.Map;

/** Reading a dotted path out of a trigger payload — SPEC-001 §2, the digest key. */
public final class Payloads {

    private Payloads() {
    }

    /** Null where any segment of the path is absent or is not a nested object. */
    public static Object at(Map<String, Object> payload, String path) {
        if (payload == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = payload;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }

        return current;
    }

    /** The digest value an event groups by: what the configuration carries, else the payload. */
    public static String digestValue(DigestConfig config, Map<String, Object> payload) {
        if (config == null) {
            return null;
        }
        if (config.digestValue() != null) {
            return config.digestValue();
        }
        Object fromPayload = at(payload, config.digestKey());

        return fromPayload == null ? null : String.valueOf(fromPayload);
    }
}
