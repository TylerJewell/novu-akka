package io.akka.novu.domain;

import java.util.Map;

/**
 * One level's stated preference — SPEC-001 §2.
 *
 * @param enabled whether the workflow is on at all; null means nothing was said, which
 *     merges as enabled (SPEC-001 §3 rule C7)
 * @param readOnly at a workflow level, closes the subscriber out of the merge entirely
 *     (rule C6)
 * @param channels the per-channel flags this level states; channels it does not name are
 *     left to whatever an earlier level said
 */
public record WorkflowPreference(Boolean enabled, Boolean readOnly, Map<Channel, Boolean> channels) {
}
