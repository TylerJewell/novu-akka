package io.akka.novu.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which channels a notification goes out on — SPEC-001 §3 rules C1-C4, C8.
 *
 * <p>Two separate things decide this and they fail differently. The candidate set is the
 * channels the workflow has an active step for, and a channel outside it is *absent* rather
 * than disabled — nothing can turn it back on. Within that set, three sources state
 * preferences and the last one to name a channel wins.
 */
public final class ChannelSelection {

    private ChannelSelection() {
    }

    /**
     * @param channels the surviving preference per channel
     * @param reasons which source last set each channel; a channel no source touched is absent
     */
    public record Result(Map<Channel, Boolean> channels, Map<Channel, PreferenceSource> reasons) {
    }

    /**
     * The candidate set: one entry per channel the workflow has an active step for, each
     * starting enabled. A channel with no active step has no entry at all (rule C1).
     */
    public static Map<Channel, Boolean> candidates(List<Channel> activeChannels) {
        Map<Channel, Boolean> candidates = new LinkedHashMap<>();
        for (Channel channel : activeChannels) {
            candidates.put(channel, true);
        }

        return candidates;
    }

    /**
     * Fold the stated preferences over the candidate set in {@link PreferenceSource}
     * declaration order. A source may only change a channel already in the set (rule C3),
     * and the last source to touch a channel is recorded as its reason even where it
     * changed nothing (rule C4).
     */
    public static Result resolve(Map<Channel, Boolean> candidateSet,
                                 Map<PreferenceSource, Map<Channel, Boolean>> stated) {
        Map<Channel, Boolean> channels = new LinkedHashMap<>(candidateSet);
        Map<Channel, PreferenceSource> reasons = new LinkedHashMap<>();

        for (PreferenceSource source : PreferenceSource.values()) {
            Map<Channel, Boolean> preference = stated.get(source);
            if (preference == null) {
                continue;
            }
            preference.forEach((channel, enabled) -> {
                if (!channels.containsKey(channel)) {
                    return;
                }
                channels.put(channel, enabled);
                reasons.put(channel, source);
            });
        }

        return new Result(channels, reasons);
    }

    /**
     * The gate a single channel step passes — rule C8. Both halves matter: a workflow the
     * subscriber turned off closes every channel at once, and a channel outside the
     * candidate set fails although nobody disabled it.
     */
    public static boolean sends(boolean workflowEnabled, Map<Channel, Boolean> channels, Channel channel) {
        return workflowEnabled && Boolean.TRUE.equals(channels.get(channel));
    }
}
