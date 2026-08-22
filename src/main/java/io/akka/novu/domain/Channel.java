package io.akka.novu.domain;

/** The channels a notification can be delivered on — SPEC-001 §3 rule C1. */
public enum Channel {
    EMAIL,
    SMS,
    IN_APP,
    CHAT,
    PUSH,
    TOOL;

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Channel fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (Channel channel : values()) {
            if (channel.wireName().equals(name.toLowerCase(java.util.Locale.ROOT))) {
                return channel;
            }
        }

        return null;
    }
}
