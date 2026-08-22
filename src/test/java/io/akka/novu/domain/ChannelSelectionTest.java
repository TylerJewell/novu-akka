package io.akka.novu.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC-001 §3 rules C1-C4, C8. */
class ChannelSelectionTest {

    private static final Map<Channel, Boolean> ALL_ON = new LinkedHashMap<>();

    static {
        for (Channel channel : Channel.values()) {
            ALL_ON.put(channel, true);
        }
    }

    @Test
    void candidateSetIsTheActiveSteps() {
        // C1. A channel with no active step is absent from the set, not present and false.
        Map<Channel, Boolean> candidates =
                ChannelSelection.candidates(List.of(Channel.EMAIL, Channel.IN_APP));

        assertEquals(List.of(Channel.EMAIL, Channel.IN_APP), List.copyOf(candidates.keySet()));
        assertFalse(candidates.containsKey(Channel.SMS));
    }

    @Test
    void lastSourceWinsForEveryOrderedPair() {
        // C2. Every ordered pair of the three sources, in both value directions, because the
        // rule is about which source wins rather than about one example of it.
        List<PreferenceSource> sources = List.of(
                PreferenceSource.WORKFLOW_RESOURCE, PreferenceSource.WORKFLOW_OVERRIDE, PreferenceSource.SUBSCRIBER);

        for (PreferenceSource first : sources) {
            for (PreferenceSource second : sources) {
                if (first == second) {
                    continue;
                }
                Map<PreferenceSource, Map<Channel, Boolean>> stated = new LinkedHashMap<>();
                stated.put(first, Map.of(Channel.EMAIL, true));
                stated.put(second, Map.of(Channel.EMAIL, false));

                ChannelSelection.Result result =
                        ChannelSelection.resolve(Map.of(Channel.EMAIL, true, Channel.IN_APP, true), stated);

                PreferenceSource expected = later(first, second);
                assertEquals(expected, result.reasons().get(Channel.EMAIL),
                        first + " against " + second);
                assertEquals(expected == first, result.channels().get(Channel.EMAIL),
                        "the winner's value is the one that stands: " + first + " against " + second);
            }
        }
    }

    @Test
    void theSubscriberHasTheLastWordWhenAllThreeDisagree() {
        // C2 over the whole chain rather than a pair.
        Map<PreferenceSource, Map<Channel, Boolean>> stated = new LinkedHashMap<>();
        stated.put(PreferenceSource.WORKFLOW_RESOURCE, Map.of(Channel.EMAIL, true, Channel.SMS, true));
        stated.put(PreferenceSource.WORKFLOW_OVERRIDE, Map.of(Channel.EMAIL, false, Channel.SMS, true));
        stated.put(PreferenceSource.SUBSCRIBER, Map.of(Channel.EMAIL, true, Channel.SMS, false));

        ChannelSelection.Result result =
                ChannelSelection.resolve(Map.of(Channel.EMAIL, false, Channel.SMS, false), stated);

        assertEquals(Map.of(Channel.EMAIL, true, Channel.SMS, false), result.channels());
    }

    @Test
    void absentChannelCannotBeRevived() {
        // C3.
        ChannelSelection.Result result = ChannelSelection.resolve(
                Map.of(Channel.EMAIL, true),
                Map.of(PreferenceSource.SUBSCRIBER, Map.of(Channel.SMS, true)));

        assertEquals(Map.of(Channel.EMAIL, true), result.channels());
        assertFalse(result.reasons().containsKey(Channel.SMS));
    }

    @Test
    void overrideReasonIsTheLastSource() {
        // C4, including the case where the later source agreed and changed nothing.
        Map<PreferenceSource, Map<Channel, Boolean>> stated = new LinkedHashMap<>();
        stated.put(PreferenceSource.WORKFLOW_RESOURCE, Map.of(Channel.EMAIL, false));
        stated.put(PreferenceSource.SUBSCRIBER, Map.of(Channel.EMAIL, false));

        ChannelSelection.Result result = ChannelSelection.resolve(Map.of(Channel.EMAIL, true), stated);

        assertFalse(result.channels().get(Channel.EMAIL));
        assertEquals(Map.of(Channel.EMAIL, PreferenceSource.SUBSCRIBER), result.reasons());
    }

    @Test
    void aSourceNamingNoChannelsRecordsNothing() {
        // C4's other half.
        ChannelSelection.Result result = ChannelSelection.resolve(
                ALL_ON, Map.of(PreferenceSource.SUBSCRIBER, Map.of()));

        assertEquals(ALL_ON, result.channels());
        assertTrue(result.reasons().isEmpty());
    }

    @Test
    void gateNeedsEnabledAndAPresentChannel() {
        // C8. Both halves, and the case where nobody disabled the channel but it is not in
        // the candidate set at all.
        ChannelSelection.Result result =
                ChannelSelection.resolve(Map.of(Channel.EMAIL, true),
                        Map.of(PreferenceSource.SUBSCRIBER, Map.of(Channel.EMAIL, true)));

        assertTrue(ChannelSelection.sends(true, result.channels(), Channel.EMAIL));
        assertFalse(ChannelSelection.sends(true, result.channels(), Channel.SMS));
        assertFalse(ChannelSelection.sends(false, result.channels(), Channel.EMAIL));
    }

    private static PreferenceSource later(PreferenceSource a, PreferenceSource b) {
        return a.ordinal() > b.ordinal() ? a : b;
    }
}
