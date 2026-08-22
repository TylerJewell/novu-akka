package io.akka.novu.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC-001 §3 rules C5-C7 — the four preference levels. */
class PreferenceMergeTest {

    private static WorkflowPreference preference(Boolean readOnly, Map<Channel, Boolean> channels) {
        return new WorkflowPreference(null, readOnly, new LinkedHashMap<>(channels));
    }

    @Test
    void laterLevelsWin() {
        // C5, over all four levels at once: each level overrides exactly the channels it names.
        PreferenceMerge.Result merged = PreferenceMerge.merge(new PreferenceMerge.Levels(
                preference(false, Map.of(Channel.EMAIL, true, Channel.SMS, true,
                        Channel.PUSH, true, Channel.CHAT, true)),
                preference(false, Map.of(Channel.EMAIL, false, Channel.SMS, true, Channel.PUSH, true)),
                preference(false, Map.of(Channel.SMS, false, Channel.PUSH, true)),
                preference(false, Map.of(Channel.PUSH, false))), false);

        assertFalse(merged.channels().get(Channel.EMAIL), "workflow-user beats workflow-resource");
        assertFalse(merged.channels().get(Channel.SMS), "subscriber-global beats workflow-user");
        assertFalse(merged.channels().get(Channel.PUSH), "subscriber-workflow beats subscriber-global");
        assertTrue(merged.channels().get(Channel.CHAT), "a channel only one level names survives");
    }

    @Test
    void readOnlyDropsBothSubscriberLevels() {
        // C6's first half.
        PreferenceMerge.Result merged = PreferenceMerge.merge(new PreferenceMerge.Levels(
                preference(true, Map.of(Channel.EMAIL, true)),
                null,
                preference(false, Map.of(Channel.EMAIL, false)),
                preference(false, Map.of(Channel.EMAIL, false))), false);

        assertTrue(merged.channels().get(Channel.EMAIL));
    }

    @Test
    void readOnlyAtTheUserLevelDropsThemToo() {
        // C6 says "any workflow-level preference", so both workflow levels are checked.
        PreferenceMerge.Result merged = PreferenceMerge.merge(new PreferenceMerge.Levels(
                preference(false, Map.of(Channel.EMAIL, true)),
                preference(true, Map.of(Channel.EMAIL, true)),
                null,
                preference(false, Map.of(Channel.EMAIL, false))), false);

        assertTrue(merged.channels().get(Channel.EMAIL));
    }

    @Test
    void excludingSubscriberPreferencesDoesTheSameWithoutReadOnly() {
        // C6's second half.
        PreferenceMerge.Result merged = PreferenceMerge.merge(new PreferenceMerge.Levels(
                preference(false, Map.of(Channel.EMAIL, true)),
                null,
                null,
                preference(false, Map.of(Channel.EMAIL, false))), true);

        assertTrue(merged.channels().get(Channel.EMAIL));
    }

    @Test
    void theBreakdownStillListsExcludedLevels() {
        // C6's last clause: what was excluded is still reported, so a reader of the breakdown
        // sees the subscriber's stated preference even though it did not apply.
        PreferenceMerge.Result merged = PreferenceMerge.merge(new PreferenceMerge.Levels(
                preference(true, Map.of(Channel.EMAIL, true)),
                null,
                null,
                preference(false, Map.of(Channel.EMAIL, false))), false);

        assertTrue(merged.channels().get(Channel.EMAIL));
        assertNotNull(merged.stated().get(PreferenceLevel.SUBSCRIBER_WORKFLOW));
        assertEquals(Boolean.FALSE,
                merged.stated().get(PreferenceLevel.SUBSCRIBER_WORKFLOW).channels().get(Channel.EMAIL));
    }

    @Test
    void anEnabledFlagLeftUnsaidMeansEnabled() {
        // C7.
        PreferenceMerge.Result merged = PreferenceMerge.merge(new PreferenceMerge.Levels(
                preference(false, Map.of(Channel.EMAIL, true)), null, null, null), false);

        assertTrue(merged.enabled());
    }

    @Test
    void anEnabledFlagSaidFalseStaysFalse() {
        // C7's boundary: the default applies only where nothing was said.
        WorkflowPreference disabled = new WorkflowPreference(false, false,
                new LinkedHashMap<>(Map.of(Channel.EMAIL, true)));

        PreferenceMerge.Result merged =
                PreferenceMerge.merge(new PreferenceMerge.Levels(disabled, null, null, null), false);

        assertFalse(merged.enabled());
    }

    @Test
    void noLevelsAtAllMergesToEnabledAndNoChannels() {
        PreferenceMerge.Result merged =
                PreferenceMerge.merge(new PreferenceMerge.Levels(null, null, null, null), false);

        assertTrue(merged.enabled());
        assertTrue(merged.channels().isEmpty());
    }
}
