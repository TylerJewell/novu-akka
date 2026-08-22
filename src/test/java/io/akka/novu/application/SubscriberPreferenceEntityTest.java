package io.akka.novu.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.novu.domain.Channel;
import io.akka.novu.domain.WorkflowPreference;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The two subscriber-side preference levels as durable state — SPEC-001 §3 rules C5, C6. */
class SubscriberPreferenceEntityTest {

    private static WorkflowPreference saying(Channel channel, boolean enabled) {
        return new WorkflowPreference(null, false, new LinkedHashMap<>(Map.of(channel, enabled)));
    }

    @Test
    void theGlobalAndPerWorkflowLevelsAreKeptApart() {
        var testKit = KeyValueEntityTestKit.of("env-1.sub-1", SubscriberPreferenceEntity::new);

        testKit.method(SubscriberPreferenceEntity::setGlobal).invoke(saying(Channel.EMAIL, false));
        testKit.method(SubscriberPreferenceEntity::setForWorkflow).invoke(
                new SubscriberPreferenceEntity.SetWorkflowPreference("tpl-1", saying(Channel.EMAIL, true)));

        SubscriberPreferenceEntity.State state =
                testKit.method(SubscriberPreferenceEntity::get).invoke().getReply();

        assertThat(state.global().channels().get(Channel.EMAIL)).isFalse();
        assertThat(state.perWorkflow().get("tpl-1").channels().get(Channel.EMAIL)).isTrue();
        assertThat(state.perWorkflow()).doesNotContainKey("tpl-2");
    }

    @Test
    void settingOneWorkflowLeavesTheOthersAlone() {
        var testKit = KeyValueEntityTestKit.of("env-1.sub-1", SubscriberPreferenceEntity::new);

        testKit.method(SubscriberPreferenceEntity::setForWorkflow).invoke(
                new SubscriberPreferenceEntity.SetWorkflowPreference("tpl-1", saying(Channel.SMS, false)));
        testKit.method(SubscriberPreferenceEntity::setForWorkflow).invoke(
                new SubscriberPreferenceEntity.SetWorkflowPreference("tpl-2", saying(Channel.SMS, true)));

        SubscriberPreferenceEntity.State state =
                testKit.method(SubscriberPreferenceEntity::get).invoke().getReply();

        assertThat(state.perWorkflow().get("tpl-1").channels().get(Channel.SMS)).isFalse();
        assertThat(state.perWorkflow().get("tpl-2").channels().get(Channel.SMS)).isTrue();
    }

    @Test
    void aSubscriberWhoHasStatedNothingHasNoLevels() {
        var testKit = KeyValueEntityTestKit.of("env-1.sub-new", SubscriberPreferenceEntity::new);

        SubscriberPreferenceEntity.State state =
                testKit.method(SubscriberPreferenceEntity::get).invoke().getReply();

        assertThat(state.global()).isNull();
        assertThat(state.perWorkflow()).isEmpty();
    }
}
