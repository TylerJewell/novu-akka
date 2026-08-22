package io.akka.novu.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.novu.domain.WorkflowPreference;

import java.util.HashMap;
import java.util.Map;

/**
 * One subscriber's stated preferences, addressed by environment and subscriber —
 * SPEC-001 §3 rules C5, C6.
 *
 * <p>Two levels live here: a global preference that applies to every workflow, and a
 * per-workflow one that overrides it. The two workflow-side levels belong to the workflow,
 * not the subscriber, and are supplied by the caller at resolution time.
 */
@Component(id = "subscriber-preference")
public class SubscriberPreferenceEntity extends KeyValueEntity<SubscriberPreferenceEntity.State> {

    /**
     * @param global the subscriber's preference across every workflow, or null
     * @param perWorkflow the subscriber's preference for one named workflow
     */
    public record State(WorkflowPreference global, Map<String, WorkflowPreference> perWorkflow) {

        static State empty() {
            return new State(null, new HashMap<>());
        }
    }

    public record SetWorkflowPreference(String workflowId, WorkflowPreference preference) {
    }

    @Override
    public State emptyState() {
        return State.empty();
    }

    public ReadOnlyEffect<State> get() {
        return effects().reply(currentState());
    }

    public Effect<State> setGlobal(WorkflowPreference preference) {
        State updated = new State(preference, currentState().perWorkflow());

        return effects().updateState(updated).thenReply(updated);
    }

    public Effect<State> setForWorkflow(SetWorkflowPreference command) {
        Map<String, WorkflowPreference> perWorkflow = new HashMap<>(currentState().perWorkflow());
        perWorkflow.put(command.workflowId(), command.preference());
        State updated = new State(currentState().global(), perWorkflow);

        return effects().updateState(updated).thenReply(updated);
    }
}
