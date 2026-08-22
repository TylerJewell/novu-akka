package io.akka.novu.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The four preference levels, folded — SPEC-001 §3 rules C5-C7.
 *
 * <p>Two workflow levels and two subscriber levels, later winning. The subscriber pair is
 * dropped whole when either workflow level marks the workflow read-only, or when the
 * caller asks for it — and what was dropped is still reported, so a reader of the
 * breakdown can see a stated subscriber preference that did not apply.
 */
public final class PreferenceMerge {

    private PreferenceMerge() {
    }

    /** Null at any level means that level stated nothing. */
    public record Levels(
            WorkflowPreference workflowResource,
            WorkflowPreference workflowUser,
            WorkflowPreference subscriberGlobal,
            WorkflowPreference subscriberWorkflow) {

        WorkflowPreference at(PreferenceLevel level) {
            return switch (level) {
                case WORKFLOW_RESOURCE -> workflowResource;
                case WORKFLOW_USER -> workflowUser;
                case SUBSCRIBER_GLOBAL -> subscriberGlobal;
                case SUBSCRIBER_WORKFLOW -> subscriberWorkflow;
            };
        }
    }

    /**
     * @param stated every level verbatim, including the ones excluded from the merge
     */
    public record Result(boolean enabled, Map<Channel, Boolean> channels,
                         Map<PreferenceLevel, WorkflowPreference> stated) {
    }

    public static Result merge(Levels levels, boolean excludeSubscriberPreferences) {
        boolean readOnly = isReadOnly(levels.workflowResource()) || isReadOnly(levels.workflowUser());
        boolean excludeSubscriber = excludeSubscriberPreferences || readOnly;

        boolean enabled = true;
        Map<Channel, Boolean> channels = new LinkedHashMap<>();
        Map<PreferenceLevel, WorkflowPreference> stated = new LinkedHashMap<>();

        for (PreferenceLevel level : PreferenceLevel.values()) {
            WorkflowPreference preference = levels.at(level);
            stated.put(level, preference);
            if (preference == null) {
                continue;
            }
            if (excludeSubscriber && isSubscriberLevel(level)) {
                continue;
            }
            if (preference.enabled() != null) {
                enabled = preference.enabled();
            }
            if (preference.channels() != null) {
                channels.putAll(preference.channels());
            }
        }

        return new Result(enabled, channels, stated);
    }

    private static boolean isSubscriberLevel(PreferenceLevel level) {
        return level == PreferenceLevel.SUBSCRIBER_GLOBAL || level == PreferenceLevel.SUBSCRIBER_WORKFLOW;
    }

    private static boolean isReadOnly(WorkflowPreference preference) {
        return preference != null && Boolean.TRUE.equals(preference.readOnly());
    }
}
